//! Moonlight Core Library for Android
//!
//! This is a Rust implementation of the moonlight-core native library,
//! providing JNI bindings for the Moonlight streaming client.

#![allow(non_upper_case_globals)]
#![allow(non_camel_case_types)]
#![allow(non_snake_case)]
#![allow(dead_code)]

mod ffi;
mod usb_ids;
mod controller;
mod callbacks;
mod jni_bridge;
mod opus;
mod crypto;

pub use ffi::*;
pub use controller::*;
pub use callbacks::*;
pub use jni_bridge::*;
pub use crypto::*;

use android_logger::Config;
use log::LevelFilter;
use once_cell::sync::OnceCell;
use std::panic;

static INIT: OnceCell<()> = OnceCell::new();

/// Initialize the library (called from JNI_OnLoad or first JNI call)
pub fn init_logging() {
    INIT.get_or_init(|| {
        android_logger::init_once(
            Config::default()
                .with_max_level(LevelFilter::Debug)
                .with_tag("moonlight-core-rs"),
        );

        // Set up panic hook to log panics to Android logcat
        panic::set_hook(Box::new(|panic_info| {
            let msg = if let Some(s) = panic_info.payload().downcast_ref::<&str>() {
                s.to_string()
            } else if let Some(s) = panic_info.payload().downcast_ref::<String>() {
                s.clone()
            } else {
                "Unknown panic".to_string()
            };

            let location = if let Some(loc) = panic_info.location() {
                format!("{}:{}:{}", loc.file(), loc.line(), loc.column())
            } else {
                "unknown location".to_string()
            };

            log::error!("RUST PANIC: {} at {}", msg, location);
        }));

        log::info!("Moonlight Core RS initialized");
    });
}

