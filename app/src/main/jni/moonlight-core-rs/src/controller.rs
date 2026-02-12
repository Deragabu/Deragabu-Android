//! Controller type detection
//!
//! Functions to identify controller types based on USB VID/PID,
//! matching the functionality of minisdl.c

use crate::usb_ids::*;
use crate::ffi::{LI_CTYPE_UNKNOWN, LI_CTYPE_XBOX, LI_CTYPE_PS, LI_CTYPE_NINTENDO};

/// Controller type enum (from Valve's controller_type.h)
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
#[repr(i32)]
pub enum ControllerType {
    None = -1,
    Unknown = 0,
    // Steam Controllers
    UnknownSteamController = 1,
    SteamController = 2,
    SteamControllerV2 = 3,
    // Other Controllers
    UnknownNonSteamController = 30,
    XBox360Controller = 31,
    XBoxOneController = 32,
    PS3Controller = 33,
    PS4Controller = 34,
    WiiController = 35,
    AppleController = 36,
    AndroidController = 37,
    SwitchProController = 38,
    SwitchJoyConLeft = 39,
    SwitchJoyConRight = 40,
    SwitchJoyConPair = 41,
    SwitchInputOnlyController = 42,
    MobileTouch = 43,
    XInputSwitchController = 44,
    PS5Controller = 45,
    XInputPS4Controller = 46,
}

/// Controller description entry
pub struct ControllerDescription {
    pub device_id: u32,
    pub controller_type: ControllerType,
    pub name: Option<&'static str>,
}

/// Create a device ID from vendor and product ID
#[inline]
pub const fn make_controller_id(vid: u16, pid: u16) -> u32 {
    ((vid as u32) << 16) | (pid as u32)
}

/// Check if joystick is Xbox One Elite
pub fn is_joystick_xbox_one_elite(vendor_id: u16, product_id: u16) -> bool {
    if vendor_id == USB_VENDOR_MICROSOFT {
        matches!(
            product_id,
            USB_PRODUCT_XBOX_ONE_ELITE_SERIES_1
                | USB_PRODUCT_XBOX_ONE_ELITE_SERIES_2
                | USB_PRODUCT_XBOX_ONE_ELITE_SERIES_2_BLUETOOTH
                | USB_PRODUCT_XBOX_ONE_ELITE_SERIES_2_BLE
        )
    } else {
        false
    }
}

/// Check if joystick is Xbox Series X
pub fn is_joystick_xbox_series_x(vendor_id: u16, product_id: u16) -> bool {
    match vendor_id {
        USB_VENDOR_MICROSOFT => matches!(
            product_id,
            USB_PRODUCT_XBOX_SERIES_X | USB_PRODUCT_XBOX_SERIES_X_BLE
        ),
        USB_VENDOR_PDP => matches!(
            product_id,
            USB_PRODUCT_XBOX_SERIES_X_VICTRIX_GAMBIT
                | USB_PRODUCT_XBOX_SERIES_X_PDP_BLUE
                | USB_PRODUCT_XBOX_SERIES_X_PDP_AFTERGLOW
        ),
        USB_VENDOR_POWERA_ALT => {
            (product_id >= 0x2001 && product_id <= 0x201a)
                || matches!(
                    product_id,
                    USB_PRODUCT_XBOX_SERIES_X_POWERA_FUSION_PRO2
                        | USB_PRODUCT_XBOX_SERIES_X_POWERA_MOGA_XP_ULTRA
                        | USB_PRODUCT_XBOX_SERIES_X_POWERA_SPECTRA
                )
        }
        USB_VENDOR_HORI => matches!(
            product_id,
            USB_PRODUCT_HORI_FIGHTING_COMMANDER_OCTA_SERIES_X | USB_PRODUCT_HORI_HORIPAD_PRO_SERIES_X
        ),
        USB_VENDOR_RAZER => matches!(
            product_id,
            USB_PRODUCT_RAZER_WOLVERINE_V2 | USB_PRODUCT_RAZER_WOLVERINE_V2_CHROMA
        ),
        USB_VENDOR_THRUSTMASTER => product_id == USB_PRODUCT_THRUSTMASTER_ESWAPX_PRO,
        USB_VENDOR_TURTLE_BEACH => matches!(
            product_id,
            USB_PRODUCT_TURTLE_BEACH_SERIES_X_REACT_R | USB_PRODUCT_TURTLE_BEACH_SERIES_X_RECON
        ),
        USB_VENDOR_8BITDO => product_id == USB_PRODUCT_8BITDO_XBOX_CONTROLLER,
        USB_VENDOR_GAMESIR => product_id == USB_PRODUCT_GAMESIR_G7,
        _ => false,
    }
}

/// Check if joystick is DualSense Edge
pub fn is_joystick_dualsense_edge(vendor_id: u16, product_id: u16) -> bool {
    vendor_id == USB_VENDOR_SONY && product_id == USB_PRODUCT_SONY_DS5_EDGE
}

/// Controller list - abbreviated version with most common controllers
/// The full list is included via include! macro from a generated file
static CONTROLLERS: &[ControllerDescription] = &[
    // PS3 Controllers
    ControllerDescription { device_id: make_controller_id(0x054c, 0x0268), controller_type: ControllerType::PS3Controller, name: None },

    // PS4 Controllers
    ControllerDescription { device_id: make_controller_id(0x054c, 0x05c4), controller_type: ControllerType::PS4Controller, name: None },
    ControllerDescription { device_id: make_controller_id(0x054c, 0x09cc), controller_type: ControllerType::PS4Controller, name: None },
    ControllerDescription { device_id: make_controller_id(0x054c, 0x0ba0), controller_type: ControllerType::PS4Controller, name: None },

    // PS5 Controllers
    ControllerDescription { device_id: make_controller_id(0x054c, 0x0ce6), controller_type: ControllerType::PS5Controller, name: None },
    ControllerDescription { device_id: make_controller_id(0x054c, 0x0df2), controller_type: ControllerType::PS5Controller, name: None },

    // Xbox 360 Controllers
    ControllerDescription { device_id: make_controller_id(0x045e, 0x028e), controller_type: ControllerType::XBox360Controller, name: Some("Xbox 360 Controller") },
    ControllerDescription { device_id: make_controller_id(0x045e, 0x028f), controller_type: ControllerType::XBox360Controller, name: Some("Xbox 360 Controller") },
    ControllerDescription { device_id: make_controller_id(0x045e, 0x0719), controller_type: ControllerType::XBox360Controller, name: Some("Xbox 360 Wireless Controller") },

    // Xbox One Controllers
    ControllerDescription { device_id: make_controller_id(0x045e, 0x02dd), controller_type: ControllerType::XBoxOneController, name: Some("Xbox One Controller") },
    ControllerDescription { device_id: make_controller_id(0x045e, 0x02e3), controller_type: ControllerType::XBoxOneController, name: Some("Xbox One Elite Controller") },
    ControllerDescription { device_id: make_controller_id(0x045e, 0x02ea), controller_type: ControllerType::XBoxOneController, name: Some("Xbox One S Controller") },
    ControllerDescription { device_id: make_controller_id(0x045e, 0x0b00), controller_type: ControllerType::XBoxOneController, name: Some("Xbox One Elite 2 Controller") },
    ControllerDescription { device_id: make_controller_id(0x045e, 0x0b12), controller_type: ControllerType::XBoxOneController, name: Some("Xbox Series X Controller") },

    // Nintendo Controllers
    ControllerDescription { device_id: make_controller_id(0x057e, 0x2006), controller_type: ControllerType::SwitchJoyConLeft, name: None },
    ControllerDescription { device_id: make_controller_id(0x057e, 0x2007), controller_type: ControllerType::SwitchJoyConRight, name: None },
    ControllerDescription { device_id: make_controller_id(0x057e, 0x2009), controller_type: ControllerType::SwitchProController, name: None },
];

/// Guess the controller type from vendor and product ID
pub fn guess_controller_type(vendor_id: i32, product_id: i32) -> i8 {
    let device_id = make_controller_id(vendor_id as u16, product_id as u16);

    for controller in CONTROLLERS {
        if device_id == controller.device_id {
            return match controller.controller_type {
                ControllerType::XBox360Controller | ControllerType::XBoxOneController => LI_CTYPE_XBOX as i8,
                ControllerType::PS3Controller | ControllerType::PS4Controller | ControllerType::PS5Controller => LI_CTYPE_PS as i8,
                ControllerType::WiiController
                | ControllerType::SwitchProController
                | ControllerType::SwitchJoyConLeft
                | ControllerType::SwitchJoyConRight
                | ControllerType::SwitchJoyConPair
                | ControllerType::SwitchInputOnlyController => LI_CTYPE_NINTENDO as i8,
                _ => LI_CTYPE_UNKNOWN as i8,
            };
        }
    }

    LI_CTYPE_UNKNOWN as i8
}

/// Check if controller has paddles (Xbox Elite or DualSense Edge)
pub fn guess_controller_has_paddles(vendor_id: i32, product_id: i32) -> bool {
    is_joystick_xbox_one_elite(vendor_id as u16, product_id as u16)
        || is_joystick_dualsense_edge(vendor_id as u16, product_id as u16)
}

/// Check if controller has share button (Xbox Series X)
pub fn guess_controller_has_share_button(vendor_id: i32, product_id: i32) -> bool {
    is_joystick_xbox_series_x(vendor_id as u16, product_id as u16)
}

