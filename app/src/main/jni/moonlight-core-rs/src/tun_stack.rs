//! Virtual TCP/IP stack for WireGuard tunnel
//!
//! This module provides a manual TCP/IP stack implementation that operates
//! at the IP packet level, suitable for use over a WireGuard tunnel.
//! Based on the proven approach from ssserver-wg's tun_stack.rs.
//!
//! Key design:
//! - Uses etherparse for packet construction and parsing
//! - Manual TCP state machine (SynSent -> Established -> close)
//! - Thread-safe with parking_lot::Mutex
//! - Outgoing packets queued for the caller to send through WireGuard
//! - Incoming data delivered to application via mpsc channels

use std::collections::{BTreeMap, HashMap, VecDeque};
use std::io;
use std::net::Ipv4Addr;
use std::sync::atomic::{AtomicU16, AtomicU32, Ordering};
use std::sync::mpsc;
use std::time::{Duration, Instant};

use etherparse::{IpNumber, Ipv4Header, TcpHeader};
use log::{info, warn};
use parking_lot::{Condvar, Mutex};

/// TCP connection state
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TcpState {
    Closed,
    SynSent,
    Established,
    FinWait1,
    FinWait2,
    CloseWait,
    LastAck,
    TimeWait,
}

/// TCP Connection identifier
#[derive(Debug, Clone, Copy, Hash, PartialEq, Eq)]
pub struct TcpConnectionId {
    pub local_addr: Ipv4Addr,
    pub local_port: u16,
    pub remote_addr: Ipv4Addr,
    pub remote_port: u16,
}

/// A segment stored for potential retransmission
struct RetransmitSegment {
    seq: u32,
    data: Vec<u8>,
    flags: u8,
    sent_at: Instant,
    retransmit_count: u32,
}

/// TCP window scale shift count for our receive window.
/// With shift=7, effective window = 65535 * 128 = ~8MB, supporting
/// high throughput even at moderate latencies (e.g., 100Mbps @ 80ms RTT).
const TCP_WINDOW_SCALE_SHIFT: u8 = 7;

/// TCP control block - tracks per-connection state
struct TcpControlBlock {
    state: TcpState,
    local_seq: u32,
    /// The initial sequence number used in SYN (for retransmission)
    initial_seq: u32,
    /// The next expected sequence number from the remote (used for ACKs)
    local_ack: u32,
    /// Send unacknowledged: the oldest byte we've sent that hasn't been ACKed
    snd_una: u32,
    tx_to_app: mpsc::SyncSender<Vec<u8>>,
    #[allow(dead_code)]
    created_at: Instant,
    last_activity: Instant,
    /// Out-of-order segment buffer: sequence_number -> data
    /// Used to reorder segments that arrive before their expected position
    reorder_buffer: BTreeMap<u32, Vec<u8>>,
    /// Maximum reorder buffer size (to prevent memory exhaustion)
    max_reorder_buffer_bytes: usize,
    /// Current reorder buffer size in bytes
    reorder_buffer_bytes: usize,
    /// Pending FIN: when a FIN arrives out-of-order (seq > local_ack),
    /// we record its effective sequence number here and defer processing
    /// until all preceding data has been received.
    pending_fin_seq: Option<u32>,
    /// Retransmission queue: segments sent but not yet acknowledged
    retransmit_queue: VecDeque<RetransmitSegment>,
    /// Current retransmission timeout (adaptive, starts at 500ms)
    rto: Duration,
}

/// Action to perform after processing a TCP packet (outside the lock)
enum TcpPacketAction {
    SendAck { seq: u32, ack: u32 },
    SendFinAck { seq: u32, ack: u32, tx: mpsc::SyncSender<Vec<u8>> },
    SendData {
        seq: u32,
        ack: u32,
        data: Vec<u8>,
        tx: mpsc::SyncSender<Vec<u8>>,
    },
    /// Multiple data segments to deliver (for reorder buffer flush)
    SendMultipleData {
        seq: u32,
        ack: u32,
        data_segments: Vec<Vec<u8>>,
        tx: mpsc::SyncSender<Vec<u8>>,
    },
    /// Deliver buffered data segments, then send FIN-ACK and signal EOF
    /// Used when FIN is received while there is buffered reorder data
    SendDataThenFinAck {
        seq: u32,
        ack: u32,
        data_segments: Vec<Vec<u8>>,
        tx: mpsc::SyncSender<Vec<u8>>,
    },
    /// Out-of-order segment buffered, send duplicate ACK
    BufferedOutOfOrder { seq: u32, ack: u32 },
    ConnectionEstablished { seq: u32, ack: u32 },
    /// Connection reset during handshake (notify waiters)
    ConnectionReset,
    /// Signal EOF to the application (e.g., on RST or unexpected close)
    SignalEof { tx: mpsc::SyncSender<Vec<u8>> },
    None,
}

/// TCP flags constants
struct TcpFlags;

impl TcpFlags {
    const SYN: u8 = 0x02;
    const ACK: u8 = 0x10;
    const FIN: u8 = 0x01;
    #[allow(dead_code)]
    const RST: u8 = 0x04;
    const PSH: u8 = 0x08;
}

/// Virtual TCP/IP stack for WireGuard tunnel.
///
/// Manages TCP connections at the IP packet level. Outgoing IP packets
/// are queued and must be sent by the caller through the WireGuard tunnel.
/// Incoming IP packets from WireGuard are processed and application data
/// is delivered through mpsc channels.
pub struct VirtualStack {
    local_ipv4: Ipv4Addr,
    tcp_connections: Mutex<HashMap<TcpConnectionId, TcpControlBlock>>,
    next_local_port: AtomicU16,
    next_seq: AtomicU32,
    /// Queued outgoing IP packets (to be sent through WireGuard)
    outgoing_packets: Mutex<Vec<Vec<u8>>>,
    /// Condition variable for TCP state changes (notifies waiters when connection established/closed)
    state_change_condvar: Condvar,
    /// Mutex used with the condvar (parking_lot Condvar works with its own Mutex)
    state_change_mutex: Mutex<()>,
}

impl VirtualStack {
    /// Create a new virtual stack with the given local IPv4 address
    pub fn new(local_ipv4: Ipv4Addr) -> Self {
        Self {
            local_ipv4,
            tcp_connections: Mutex::new(HashMap::new()),
            next_local_port: AtomicU16::new(49152),
            next_seq: AtomicU32::new(1_000_000),
            outgoing_packets: Mutex::new(Vec::new()),
            state_change_condvar: Condvar::new(),
            state_change_mutex: Mutex::new(()),
        }
    }

    /// Wait for a TCP connection state change with timeout.
    /// Returns true if notified, false if timed out.
    pub fn wait_for_state_change(&self, timeout: Duration) -> bool {
        let mut guard = self.state_change_mutex.lock();
        let result = self.state_change_condvar.wait_for(&mut guard, timeout);
        !result.timed_out()
    }

    /// Notify all waiters that TCP state has changed
    fn notify_state_change(&self) {
        self.state_change_condvar.notify_all();
    }

    fn allocate_port(&self) -> u16 {
        let port = self.next_local_port.fetch_add(1, Ordering::Relaxed);
        if port >= 65000 {
            self.next_local_port.store(49152, Ordering::Relaxed);
        }
        port
    }

    fn generate_initial_seq(&self) -> u32 {
        // Use time-based increment for reasonable ISN diversity
        let increment = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .subsec_nanos() % 1_000_000
            + 1;
        self.next_seq.fetch_add(increment, Ordering::Relaxed)
    }

    /// Initiate a TCP connection to a remote endpoint.
    /// Returns the connection ID and a receiver channel for incoming data.
    pub fn tcp_connect(
        &self,
        remote_addr: Ipv4Addr,
        remote_port: u16,
    ) -> (TcpConnectionId, mpsc::Receiver<Vec<u8>>) {
        let local_port = self.allocate_port();
        let initial_seq = self.generate_initial_seq();

        let conn_id = TcpConnectionId {
            local_addr: self.local_ipv4,
            local_port,
            remote_addr,
            remote_port,
        };

        // Larger channel buffer to support TCP window scaling (up to ~8MB window).
        // With 2048 entries * ~1360 bytes MSS = ~2.8MB effective buffer.
        let (tx, rx) = mpsc::sync_channel::<Vec<u8>>(2048);

        let now = Instant::now();
        let tcb = TcpControlBlock {
            state: TcpState::SynSent,
            local_seq: initial_seq,
            initial_seq, // Store for SYN retransmission
            local_ack: 0,
            snd_una: initial_seq, // Will be updated on SYN-ACK
            tx_to_app: tx,
            created_at: now,
            last_activity: now,
            reorder_buffer: BTreeMap::new(),
            max_reorder_buffer_bytes: 1024 * 1024, // 1MB max reorder buffer
            reorder_buffer_bytes: 0,
            pending_fin_seq: None,
            retransmit_queue: VecDeque::new(),
            rto: Duration::from_millis(500),
        };

        {
            let mut conns = self.tcp_connections.lock();
            conns.insert(conn_id, tcb);
        }

        // Send SYN
        self.send_tcp_packet(&conn_id, initial_seq, 0, TcpFlags::SYN, &[]);

        info!(
            "Initiated TCP connection to {}:{}",
            remote_addr, remote_port
        );

        (conn_id, rx)
    }

    /// Send data on an established TCP connection
    pub fn tcp_send(&self, conn_id: &TcpConnectionId, data: &[u8]) -> io::Result<()> {
        let (mut seq, ack) = {
            let mut conns = self.tcp_connections.lock();
            let tcb = conns.get_mut(conn_id).ok_or_else(|| {
                io::Error::new(io::ErrorKind::NotConnected, "Connection not found")
            })?;

            if tcb.state != TcpState::Established && tcb.state != TcpState::CloseWait {
                return Err(io::Error::new(
                    io::ErrorKind::NotConnected,
                    "Connection not in a sendable state",
                ));
            }

            tcb.last_activity = Instant::now();
            let seq = tcb.local_seq;
            tcb.local_seq = tcb.local_seq.wrapping_add(data.len() as u32);
            (seq, tcb.local_ack)
        };

        // Segment data by a conservative MSS (1360 bytes for WG tunnel)
        // MTU 1420 - IP header 20 - TCP header 20 - some margin = 1360
        let mss = 1360usize;
        let now = Instant::now();
        for chunk in data.chunks(mss) {
            let flags = if chunk.as_ptr() as usize + chunk.len()
                == data.as_ptr() as usize + data.len()
            {
                // Last (or only) segment: set PSH
                TcpFlags::ACK | TcpFlags::PSH
            } else {
                TcpFlags::ACK
            };
            self.send_tcp_packet(conn_id, seq, ack, flags, chunk);

            // Store segment for potential retransmission
            {
                let mut conns = self.tcp_connections.lock();
                if let Some(tcb) = conns.get_mut(conn_id) {
                    tcb.retransmit_queue.push_back(RetransmitSegment {
                        seq,
                        data: chunk.to_vec(),
                        flags,
                        sent_at: now,
                        retransmit_count: 0,
                    });
                }
            }

            seq = seq.wrapping_add(chunk.len() as u32);
        }

        Ok(())
    }

    /// Close a TCP connection gracefully
    pub fn tcp_close(&self, conn_id: &TcpConnectionId) -> io::Result<()> {
        let (seq, ack) = {
            let mut conns = self.tcp_connections.lock();
            if let Some(tcb) = conns.get_mut(conn_id) {
                // Clear retransmit queue on close - no point retransmitting
                tcb.retransmit_queue.clear();
                match tcb.state {
                    TcpState::Established => {
                        // Active close: we initiate FIN
                        tcb.state = TcpState::FinWait1;
                        (tcb.local_seq, tcb.local_ack)
                    }
                    TcpState::CloseWait => {
                        // Passive close: server already FIN'd, now we FIN too
                        // Next state is LastAck (waiting for ACK of our FIN)
                        tcb.state = TcpState::LastAck;
                        (tcb.local_seq, tcb.local_ack)
                    }
                    _ => return Ok(()),
                }
            } else {
                return Ok(());
            }
        };

        self.send_tcp_packet(conn_id, seq, ack, TcpFlags::FIN | TcpFlags::ACK, &[]);
        Ok(())
    }

    /// Check if a connection is in the Established state
    pub fn is_tcp_established(&self, conn_id: &TcpConnectionId) -> bool {
        let conns = self.tcp_connections.lock();
        conns
            .get(conn_id)
            .map(|tcb| tcb.state == TcpState::Established)
            .unwrap_or(false)
    }

    /// Get the current state of a TCP connection
    pub fn get_tcp_state(&self, conn_id: &TcpConnectionId) -> Option<TcpState> {
        let conns = self.tcp_connections.lock();
        conns.get(conn_id).map(|tcb| tcb.state)
    }

    /// Remove a TCP connection from tracking
    pub fn remove_tcp_connection(&self, conn_id: &TcpConnectionId) {
        let mut conns = self.tcp_connections.lock();
        conns.remove(conn_id);
    }

    /// Resend SYN for a connection in SynSent state.
    /// Returns true if SYN was resent, false if connection is not in SynSent state.
    pub fn resend_syn_if_pending(&self, conn_id: &TcpConnectionId) -> bool {
        let initial_seq = {
            let conns = self.tcp_connections.lock();
            if let Some(tcb) = conns.get(conn_id) {
                if tcb.state == TcpState::SynSent {
                    Some(tcb.initial_seq)
                } else {
                    None
                }
            } else {
                None
            }
        };

        if let Some(seq) = initial_seq {
            self.send_tcp_packet(conn_id, seq, 0, TcpFlags::SYN, &[]);
            true
        } else {
            false
        }
    }

    /// Check all connections for segments that need retransmission.
    /// Returns the number of segments retransmitted.
    pub fn check_retransmissions(&self) -> usize {
        let now = Instant::now();
        let max_retransmits: u32 = 8;
        let max_rto = Duration::from_secs(8);

        // Collect segments that need retransmission (under lock)
        let mut to_retransmit: Vec<(TcpConnectionId, u32, Vec<u8>, u8, u32)> = Vec::new();
        {
            let mut conns = self.tcp_connections.lock();
            for (conn_id, tcb) in conns.iter_mut() {
                if tcb.state != TcpState::Established && tcb.state != TcpState::CloseWait {
                    continue;
                }
                for seg in tcb.retransmit_queue.iter_mut() {
                    if now.duration_since(seg.sent_at) >= tcb.rto {
                        if seg.retransmit_count >= max_retransmits {
                            warn!("TCP retransmit limit reached for {}:{} seq={}",
                                  conn_id.remote_addr, conn_id.remote_port, seg.seq);
                            continue;
                        }
                        to_retransmit.push((
                            *conn_id,
                            seg.seq,
                            seg.data.clone(),
                            seg.flags,
                            tcb.local_ack,
                        ));
                        seg.retransmit_count += 1;
                        seg.sent_at = now;
                        // Exponential backoff for RTO
                        tcb.rto = (tcb.rto * 2).min(max_rto);
                    }
                    // Only retransmit the first unACKed segment per connection (go-back-N style)
                    break;
                }
            }
        }

        // Send retransmit packets outside the lock
        let count = to_retransmit.len();
        for (conn_id, seq, data, flags, ack) in to_retransmit {
            self.send_tcp_packet(&conn_id, seq, ack, flags, &data);
        }
        count
    }

    /// Take all queued outgoing IP packets (caller sends them through WireGuard)
    pub fn take_outgoing_packets(&self) -> Vec<Vec<u8>> {
        std::mem::take(&mut *self.outgoing_packets.lock())
    }

    /// Process an incoming IP packet received from WireGuard
    pub fn process_incoming_packet(&self, packet: &[u8]) {
        if packet.len() < 20 {
            return;
        }

        let version = (packet[0] >> 4) & 0x0F;
        if version != 4 {
            return;
        }

        // Parse IPv4 header
        let (ip_header, payload) = match Ipv4Header::from_slice(packet) {
            Ok(r) => r,
            Err(_) => return,
        };

        let src_ip = Ipv4Addr::from(ip_header.source);
        let dst_ip = Ipv4Addr::from(ip_header.destination);

        match ip_header.protocol {
            IpNumber::TCP => self.process_tcp_packet(src_ip, dst_ip, payload),
            _ => {}
        }
    }

    fn process_tcp_packet(&self, src_ip: Ipv4Addr, dst_ip: Ipv4Addr, payload: &[u8]) {
        let (tcp_header, tcp_payload) = match TcpHeader::from_slice(payload) {
            Ok(r) => r,
            Err(_) => return,
        };

        let conn_id = TcpConnectionId {
            local_addr: dst_ip,
            local_port: tcp_header.destination_port,
            remote_addr: src_ip,
            remote_port: tcp_header.source_port,
        };

        info!("process_tcp_packet: {}:{} -> {}:{}, flags: SYN={}, ACK={}, FIN={}, RST={}",
            src_ip, tcp_header.source_port, dst_ip, tcp_header.destination_port,
            tcp_header.syn, tcp_header.ack, tcp_header.fin, tcp_header.rst);

        // Process packet while holding lock, determine action to take
        let action = {
            let mut conns = self.tcp_connections.lock();

            if let Some(tcb) = conns.get_mut(&conn_id) {
                info!("process_tcp_packet: found connection, state={:?}", tcb.state);
                match tcb.state {
                    TcpState::SynSent => {
                        if tcp_header.syn && tcp_header.ack {
                            // SYN-ACK received - complete handshake
                            tcb.local_ack = tcp_header.sequence_number.wrapping_add(1);
                            tcb.local_seq = tcp_header.acknowledgment_number;
                            tcb.snd_una = tcp_header.acknowledgment_number;
                            tcb.state = TcpState::Established;
                            tcb.last_activity = Instant::now();
                            TcpPacketAction::ConnectionEstablished {
                                seq: tcb.local_seq,
                                ack: tcb.local_ack,
                            }
                        } else if tcp_header.rst {
                            tcb.state = TcpState::Closed;
                            tcb.last_activity = Instant::now();
                            warn!("Connection reset during handshake");
                            TcpPacketAction::ConnectionReset
                        } else {
                            TcpPacketAction::None
                        }
                    }
                    TcpState::Established => {
                        tcb.last_activity = Instant::now();

                        // Process ACK number - advance snd_una and clear retransmit buffer
                        if tcp_header.ack {
                            let ack_num = tcp_header.acknowledgment_number;
                            // Only advance if ACK is within valid range
                            let ack_advance = ack_num.wrapping_sub(tcb.snd_una) as i32;
                            if ack_advance > 0 {
                                tcb.snd_una = ack_num;
                                // Remove fully acknowledged segments from retransmit queue
                                while let Some(front) = tcb.retransmit_queue.front() {
                                    let seg_end = front.seq.wrapping_add(front.data.len() as u32);
                                    // If snd_una >= seg_end, this segment is fully ACKed
                                    if seg_end.wrapping_sub(tcb.snd_una) as i32 <= 0 {
                                        tcb.retransmit_queue.pop_front();
                                    } else {
                                        break;
                                    }
                                }
                                // Reset RTO on successful ACK
                                tcb.rto = Duration::from_millis(500);
                            }
                        }

                        if tcp_header.rst {
                            tcb.state = TcpState::Closed;
                            tcb.last_activity = Instant::now();
                            tcb.retransmit_queue.clear();
                            warn!("Connection reset by peer");
                            TcpPacketAction::SignalEof { tx: tcb.tx_to_app.clone() }
                        } else if tcp_header.fin {
                            // FIN received - compute where the FIN sits in the sequence space
                            // FIN consumes one seq after any payload
                            let fin_seq = tcp_header.sequence_number
                                .wrapping_add(tcp_payload.len() as u32);
                            let seq_diff = tcp_header.sequence_number
                                .wrapping_sub(tcb.local_ack) as i32;

                            if seq_diff <= 0 {
                                // In-order (or duplicate) FIN
                                // Deliver any payload from this FIN packet
                                let mut segments = Vec::new();
                                if !tcp_payload.is_empty() && seq_diff == 0 {
                                    tcb.local_ack = tcb.local_ack
                                        .wrapping_add(tcp_payload.len() as u32);
                                    segments.push(tcp_payload.to_vec());
                                }
                                // Flush contiguous reorder buffer
                                while let Some(entry) = tcb.reorder_buffer.first_entry() {
                                    if *entry.key() == tcb.local_ack {
                                        let data = entry.remove();
                                        tcb.local_ack = tcb.local_ack
                                            .wrapping_add(data.len() as u32);
                                        tcb.reorder_buffer_bytes -= data.len();
                                        segments.push(data);
                                    } else {
                                        break;
                                    }
                                }
                                tcb.state = TcpState::CloseWait;
                                tcb.local_ack = fin_seq.wrapping_add(1); // ACK the FIN

                                if !segments.is_empty() {
                                    TcpPacketAction::SendDataThenFinAck {
                                        seq: tcb.local_seq,
                                        ack: tcb.local_ack,
                                        data_segments: segments,
                                        tx: tcb.tx_to_app.clone(),
                                    }
                                } else {
                                    TcpPacketAction::SendFinAck {
                                        seq: tcb.local_seq,
                                        ack: tcb.local_ack,
                                        tx: tcb.tx_to_app.clone(),
                                    }
                                }
                            } else {
                                // Out-of-order FIN (arrives before preceding data)
                                tcb.pending_fin_seq = Some(fin_seq);

                                // Buffer any data payload from the FIN packet
                                if !tcp_payload.is_empty() {
                                    let data = tcp_payload.to_vec();
                                    if tcb.reorder_buffer_bytes + data.len()
                                        <= tcb.max_reorder_buffer_bytes
                                    {
                                        tcb.reorder_buffer_bytes += data.len();
                                        tcb.reorder_buffer
                                            .insert(tcp_header.sequence_number, data);
                                    }
                                }

                                // Send duplicate ACK for what we have so far
                                TcpPacketAction::BufferedOutOfOrder {
                                    seq: tcb.local_seq,
                                    ack: tcb.local_ack,
                                }
                            }
                        } else if !tcp_payload.is_empty() {
                            // Data received - check if it's in sequence
                            let pkt_seq = tcp_header.sequence_number;
                            let expected_seq = tcb.local_ack;
                            
                            // Check for duplicate/retransmit (seq < expected)
                            // Use wrapping comparison for sequence numbers
                            let seq_diff = pkt_seq.wrapping_sub(expected_seq) as i32;
                            
                            if seq_diff < 0 {
                                // Duplicate or retransmit - just ACK
                                TcpPacketAction::SendAck {
                                    seq: tcb.local_seq,
                                    ack: tcb.local_ack,
                                }
                            } else if seq_diff == 0 {
                                // In-order segment
                                tcb.local_ack = pkt_seq.wrapping_add(tcp_payload.len() as u32);
                                
                                // Collect this segment and any contiguous buffered segments
                                let mut segments = vec![tcp_payload.to_vec()];
                                
                                // Check reorder buffer for contiguous segments
                                while let Some(entry) = tcb.reorder_buffer.first_entry() {
                                    if *entry.key() == tcb.local_ack {
                                        let data = entry.remove();
                                        tcb.local_ack = tcb.local_ack.wrapping_add(data.len() as u32);
                                        tcb.reorder_buffer_bytes -= data.len();
                                        segments.push(data);
                                    } else {
                                        break;
                                    }
                                }
                                
                                // Check if a pending out-of-order FIN is now in sequence
                                if let Some(fin_seq) = tcb.pending_fin_seq {
                                    if tcb.local_ack == fin_seq {
                                        // All data before FIN received - process the FIN now
                                        tcb.pending_fin_seq = None;
                                        tcb.state = TcpState::CloseWait;
                                        tcb.local_ack = fin_seq.wrapping_add(1);
                                        TcpPacketAction::SendDataThenFinAck {
                                            seq: tcb.local_seq,
                                            ack: tcb.local_ack,
                                            data_segments: segments,
                                            tx: tcb.tx_to_app.clone(),
                                        }
                                    } else {
                                        // Still waiting for more data before the FIN
                                        if segments.len() == 1 {
                                            TcpPacketAction::SendData {
                                                seq: tcb.local_seq,
                                                ack: tcb.local_ack,
                                                data: segments.pop().unwrap(),
                                                tx: tcb.tx_to_app.clone(),
                                            }
                                        } else {
                                            TcpPacketAction::SendMultipleData {
                                                seq: tcb.local_seq,
                                                ack: tcb.local_ack,
                                                data_segments: segments,
                                                tx: tcb.tx_to_app.clone(),
                                            }
                                        }
                                    }
                                } else if segments.len() == 1 {
                                    TcpPacketAction::SendData {
                                        seq: tcb.local_seq,
                                        ack: tcb.local_ack,
                                        data: segments.pop().unwrap(),
                                        tx: tcb.tx_to_app.clone(),
                                    }
                                } else {
                                    TcpPacketAction::SendMultipleData {
                                        seq: tcb.local_seq,
                                        ack: tcb.local_ack,
                                        data_segments: segments,
                                        tx: tcb.tx_to_app.clone(),
                                    }
                                }
                            } else {
                                // Out-of-order segment (seq > expected) - buffer it
                                let data = tcp_payload.to_vec();
                                
                                // Check buffer size limit
                                if tcb.reorder_buffer_bytes + data.len() <= tcb.max_reorder_buffer_bytes {
                                    tcb.reorder_buffer_bytes += data.len();
                                    tcb.reorder_buffer.insert(pkt_seq, data);
                                    
                                    // Send duplicate ACK to trigger fast retransmit
                                    TcpPacketAction::BufferedOutOfOrder {
                                        seq: tcb.local_seq,
                                        ack: tcb.local_ack, // ACK the last in-order byte
                                    }
                                } else {
                                    warn!("Reorder buffer full, dropping out-of-order segment");
                                    TcpPacketAction::SendAck {
                                        seq: tcb.local_seq,
                                        ack: tcb.local_ack,
                                    }
                                }
                            }
                        } else {
                            // Pure ACK - already processed ACK number above
                            TcpPacketAction::None
                        }
                    }
                    TcpState::FinWait1 => {
                        tcb.last_activity = Instant::now();
                        if tcp_header.rst {
                            tcb.state = TcpState::Closed;
                            TcpPacketAction::None
                        } else if tcp_header.fin && tcp_header.ack {
                            tcb.state = TcpState::TimeWait;
                            // Account for any data payload + the FIN sequence number
                            tcb.local_ack = tcp_header
                                .sequence_number
                                .wrapping_add(tcp_payload.len() as u32)
                                .wrapping_add(1);
                            TcpPacketAction::SendAck {
                                seq: tcb.local_seq,
                                ack: tcb.local_ack,
                            }
                        } else if tcp_header.ack {
                            tcb.state = TcpState::FinWait2;
                            TcpPacketAction::None
                        } else {
                            TcpPacketAction::None
                        }
                    }
                    TcpState::FinWait2 => {
                        tcb.last_activity = Instant::now();
                        if tcp_header.rst {
                            tcb.state = TcpState::Closed;
                            TcpPacketAction::None
                        } else if tcp_header.fin {
                            tcb.state = TcpState::TimeWait;
                            // Account for any data payload + the FIN sequence number
                            tcb.local_ack = tcp_header
                                .sequence_number
                                .wrapping_add(tcp_payload.len() as u32)
                                .wrapping_add(1);
                            TcpPacketAction::SendAck {
                                seq: tcb.local_seq,
                                ack: tcb.local_ack,
                            }
                        } else {
                            TcpPacketAction::None
                        }
                    }
                    TcpState::CloseWait => {
                        tcb.last_activity = Instant::now();
                        if tcp_header.rst {
                            tcb.state = TcpState::Closed;
                        }
                        // In CloseWait, we haven't sent our FIN yet, just waiting for app to close
                        TcpPacketAction::None
                    }
                    TcpState::LastAck => {
                        tcb.last_activity = Instant::now();
                        // Waiting for final ACK of our FIN
                        if tcp_header.ack {
                            tcb.state = TcpState::Closed;
                            tcb.last_activity = Instant::now(); // Reset for grace period
                        }
                        TcpPacketAction::None
                    }
                    TcpState::TimeWait => {
                        tcb.last_activity = Instant::now();
                        // Re-ACK retransmitted FINs to help remote complete teardown
                        if tcp_header.fin {
                            TcpPacketAction::SendAck {
                                seq: tcb.local_seq,
                                ack: tcb.local_ack,
                            }
                        } else {
                            TcpPacketAction::None
                        }
                    }
                    _ => TcpPacketAction::None,
                }
            } else {
                warn!("process_tcp_packet: no connection found for {}:{} -> {}:{}",
                      src_ip, tcp_header.source_port, dst_ip, tcp_header.destination_port);
                if !tcp_header.rst {
                    // Send RST to inform remote side this connection doesn't exist.
                    // This stops retransmissions and cleans up server-side state.
                    let orphan_id = TcpConnectionId {
                        local_addr: dst_ip,
                        local_port: tcp_header.destination_port,
                        remote_addr: src_ip,
                        remote_port: tcp_header.source_port,
                    };
                    if tcp_header.ack {
                        // If incoming has ACK, use its ack number as our seq
                        self.send_tcp_packet(
                            &orphan_id,
                            tcp_header.acknowledgment_number,
                            0,
                            TcpFlags::RST,
                            &[],
                        );
                    } else {
                        // Otherwise, send RST+ACK
                        let ack_num = tcp_header
                            .sequence_number
                            .wrapping_add(tcp_payload.len() as u32)
                            .wrapping_add(
                                if tcp_header.syn || tcp_header.fin { 1 } else { 0 },
                            );
                        self.send_tcp_packet(
                            &orphan_id,
                            0,
                            ack_num,
                            TcpFlags::RST | TcpFlags::ACK,
                            &[],
                        );
                    }
                }
                TcpPacketAction::None
            }
        };

        // Execute action with lock released
        match action {
            TcpPacketAction::SendAck { seq, ack } => {
                self.send_tcp_packet(&conn_id, seq, ack, TcpFlags::ACK, &[]);
            }
            TcpPacketAction::SendFinAck { seq, ack, tx } => {
                // ACK the FIN from remote
                self.send_tcp_packet(&conn_id, seq, ack, TcpFlags::ACK, &[]);
                // Signal EOF to the application so recv() returns immediately.
                // Stay in CloseWait - our FIN will be sent when the app calls tcp_close.
                // This supports half-close: the app can still send data before closing.
                let _ = tx.send(Vec::new());
            }
            TcpPacketAction::SendData { seq, ack, data, tx } => {
                // ACK the data
                self.send_tcp_packet(&conn_id, seq, ack, TcpFlags::ACK, &[]);
                // Forward data to application
                if tx.send(data).is_err() {
                    warn!("TCP data channel disconnected for {:?}", conn_id);
                    // Mark connection as closed since receiver dropped
                    let mut conns = self.tcp_connections.lock();
                    if let Some(tcb) = conns.get_mut(&conn_id) {
                        tcb.state = TcpState::Closed;
                        tcb.last_activity = Instant::now();
                    }
                }
            }
            TcpPacketAction::SendMultipleData { seq, ack, data_segments, tx } => {
                // ACK all the data
                self.send_tcp_packet(&conn_id, seq, ack, TcpFlags::ACK, &[]);
                // Forward all segments to application in order
                for data in data_segments {
                    if tx.send(data).is_err() {
                        warn!("TCP data channel disconnected for {:?}", conn_id);
                        let mut conns = self.tcp_connections.lock();
                        if let Some(tcb) = conns.get_mut(&conn_id) {
                            tcb.state = TcpState::Closed;
                            tcb.last_activity = Instant::now();
                        }
                        break;
                    }
                }
            }
            TcpPacketAction::SendDataThenFinAck { seq, ack, data_segments, tx } => {
                // ACK all the data + FIN from remote
                self.send_tcp_packet(&conn_id, seq, ack, TcpFlags::ACK, &[]);
                // Forward all segments to application in order
                for data in data_segments {
                    if tx.send(data).is_err() {
                        warn!("TCP data channel disconnected for {:?}", conn_id);
                        break;
                    }
                }
                // Signal EOF - remote has closed its end.
                // Stay in CloseWait - our FIN will be sent when the app calls tcp_close.
                // This supports half-close: the app can still send data before closing.
                let _ = tx.send(Vec::new());
            }
            TcpPacketAction::BufferedOutOfOrder { seq, ack } => {
                // Send duplicate ACK to indicate gap (triggers fast retransmit on sender)
                self.send_tcp_packet(&conn_id, seq, ack, TcpFlags::ACK, &[]);
            }
            TcpPacketAction::SignalEof { tx } => {
                // Signal EOF to the application (connection was reset)
                let _ = tx.send(Vec::new());
            }
            TcpPacketAction::ConnectionEstablished { seq, ack } => {
                // Send ACK to complete 3-way handshake
                self.send_tcp_packet(&conn_id, seq, ack, TcpFlags::ACK, &[]);
                info!(
                    "TCP connection established to {}:{}",
                    conn_id.remote_addr, conn_id.remote_port
                );
                // Notify waiters (e.g., wg_socket_connect polling loop)
                self.notify_state_change();
            }
            TcpPacketAction::ConnectionReset => {
                // Notify waiters that connection was reset
                self.notify_state_change();
            }
            TcpPacketAction::None => {}
        }
    }

    /// Build and queue a TCP packet for sending
    fn send_tcp_packet(
        &self,
        conn_id: &TcpConnectionId,
        seq: u32,
        ack: u32,
        flags: u8,
        payload: &[u8],
    ) {
        let mut tcp_header = TcpHeader::new(
            conn_id.local_port,
            conn_id.remote_port,
            seq,
            65535, // window size (with WS=7, effective = 65535 * 128 = ~8MB)
        );
        tcp_header.acknowledgment_number = ack;
        tcp_header.syn = (flags & TcpFlags::SYN) != 0;
        tcp_header.ack = (flags & TcpFlags::ACK) != 0;
        tcp_header.fin = (flags & TcpFlags::FIN) != 0;
        tcp_header.rst = (flags & TcpFlags::RST) != 0;
        tcp_header.psh = (flags & TcpFlags::PSH) != 0;

        // Add TCP options for SYN packets: MSS + Window Scale
        // This enables TCP window scaling (RFC 7323), allowing effective
        // receive window up to ~8MB instead of the 65535 byte limit.
        if tcp_header.syn {
            // TCP options (8 bytes, 4-byte aligned):
            // MSS: kind=2, len=4, value=1360 (conservative for WG tunnel)
            // NOP: kind=1 (padding for alignment)
            // Window Scale: kind=3, len=3, shift=TCP_WINDOW_SCALE_SHIFT
            let mss: u16 = 1360;
            let options: [u8; 8] = [
                2, 4, (mss >> 8) as u8, (mss & 0xff) as u8, // MSS
                1,                                             // NOP
                3, 3, TCP_WINDOW_SCALE_SHIFT,                  // Window Scale
            ];
            if let Err(e) = tcp_header.set_options_raw(&options) {
                warn!("Failed to set TCP SYN options: {:?}", e);
            }
        }

        let src = conn_id.local_addr;
        let dst = conn_id.remote_addr;

        let ip_payload_len = tcp_header.header_len() as usize + payload.len();
        let ip_header = match Ipv4Header::new(
            ip_payload_len as u16,
            64, // TTL
            IpNumber::TCP,
            src.octets(),
            dst.octets(),
        ) {
            Ok(h) => h,
            Err(e) => {
                warn!("Failed to create IPv4 header: {}", e);
                return;
            }
        };

        // Calculate TCP checksum using IPv4 pseudo-header
        tcp_header.checksum = match tcp_header.calc_checksum_ipv4(&ip_header, payload) {
            Ok(c) => c,
            Err(e) => {
                warn!("Failed to calculate TCP checksum: {}", e);
                return;
            }
        };

        let mut packet = Vec::with_capacity(20 + ip_payload_len);
        if let Err(e) = ip_header.write(&mut packet) {
            warn!("Failed to write IPv4 header: {}", e);
            return;
        }
        if let Err(e) = tcp_header.write(&mut packet) {
            warn!("Failed to write TCP header: {}", e);
            return;
        }
        packet.extend_from_slice(payload);

        self.outgoing_packets.lock().push(packet);
    }

    /// Clean up stale TCP connections. Returns number removed.
    pub fn cleanup_stale_connections(&self) -> usize {
        let mut conns = self.tcp_connections.lock();
        let before = conns.len();
        let now = Instant::now();
        conns.retain(|id, tcb| {
            let stale = match tcb.state {
                TcpState::TimeWait => now.duration_since(tcb.last_activity).as_secs() > 60,
                // Give Closed connections a brief grace period for any in-flight packets
                TcpState::Closed => now.duration_since(tcb.last_activity).as_secs() > 5,
                TcpState::SynSent => now.duration_since(tcb.created_at).as_secs() > 30,
                TcpState::FinWait1 | TcpState::FinWait2 | TcpState::CloseWait | TcpState::LastAck => {
                    now.duration_since(tcb.last_activity).as_secs() > 120
                }
                TcpState::Established => {
                    now.duration_since(tcb.last_activity).as_secs() > 600
                }
            };
            if stale {
                info!(
                    "Cleaning up stale TCP connection {:?} in state {:?}",
                    id, tcb.state
                );
            }
            !stale
        });
        before - conns.len()
    }

    /// Get number of active TCP connections
    pub fn connection_count(&self) -> usize {
        self.tcp_connections.lock().len()
    }
}
