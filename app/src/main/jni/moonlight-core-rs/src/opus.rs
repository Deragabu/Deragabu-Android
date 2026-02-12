//! Opus decoder FFI bindings
//!
//! This module provides FFI declarations for the Opus multistream decoder.

use libc::{c_int, c_uchar};

/// Opus multistream decoder opaque type
#[repr(C)]
pub struct OpusMSDecoder {
    _private: [u8; 0],
}

// External C functions from libopus
#[link(name = "opus")]
extern "C" {
    /// Create a multistream decoder
    pub fn opus_multistream_decoder_create(
        sample_rate: c_int,
        channels: c_int,
        streams: c_int,
        coupled_streams: c_int,
        mapping: *const c_uchar,
        error: *mut c_int,
    ) -> *mut OpusMSDecoder;

    /// Decode a multistream packet
    pub fn opus_multistream_decode(
        st: *mut OpusMSDecoder,
        data: *const c_uchar,
        len: c_int,
        pcm: *mut i16,
        frame_size: c_int,
        decode_fec: c_int,
    ) -> c_int;

    /// Destroy a multistream decoder
    pub fn opus_multistream_decoder_destroy(st: *mut OpusMSDecoder);
}

