package com.limelight.binding.input;

/**
 * Controller type detection utilities.
 *
 * This class identifies controller types based on USB VID/PID,
 * replacing the native Rust implementation for better performance
 * (eliminates JNI call overhead for this infrequently called code).
 */
public class ControllerDetection {

    // Vendor IDs
    private static final int USB_VENDOR_8BITDO = 0x2dc8;
    private static final int USB_VENDOR_GAMESIR = 0x3537;
    private static final int USB_VENDOR_HORI = 0x0f0d;
    private static final int USB_VENDOR_MICROSOFT = 0x045e;
    private static final int USB_VENDOR_NINTENDO = 0x057e;
    private static final int USB_VENDOR_PDP = 0x0e6f;
    private static final int USB_VENDOR_POWERA_ALT = 0x20d6;
    private static final int USB_VENDOR_RAZER = 0x1532;
    private static final int USB_VENDOR_SONY = 0x054c;
    private static final int USB_VENDOR_THRUSTMASTER = 0x044f;
    private static final int USB_VENDOR_TURTLE_BEACH = 0x10f5;

    // Product IDs - Xbox Elite
    private static final int USB_PRODUCT_XBOX_ONE_ELITE_SERIES_1 = 0x02e3;
    private static final int USB_PRODUCT_XBOX_ONE_ELITE_SERIES_2 = 0x0b00;
    private static final int USB_PRODUCT_XBOX_ONE_ELITE_SERIES_2_BLUETOOTH = 0x0b05;
    private static final int USB_PRODUCT_XBOX_ONE_ELITE_SERIES_2_BLE = 0x0b22;

    // Product IDs - Xbox Series X
    private static final int USB_PRODUCT_XBOX_SERIES_X = 0x0b12;
    private static final int USB_PRODUCT_XBOX_SERIES_X_BLE = 0x0b13;
    private static final int USB_PRODUCT_XBOX_SERIES_X_VICTRIX_GAMBIT = 0x02d6;
    private static final int USB_PRODUCT_XBOX_SERIES_X_PDP_BLUE = 0x02d9;
    private static final int USB_PRODUCT_XBOX_SERIES_X_PDP_AFTERGLOW = 0x02da;
    private static final int USB_PRODUCT_XBOX_SERIES_X_POWERA_FUSION_PRO2 = 0x4001;
    private static final int USB_PRODUCT_XBOX_SERIES_X_POWERA_MOGA_XP_ULTRA = 0x890b;
    private static final int USB_PRODUCT_XBOX_SERIES_X_POWERA_SPECTRA = 0x4002;
    private static final int USB_PRODUCT_HORI_FIGHTING_COMMANDER_OCTA_SERIES_X = 0x0150;
    private static final int USB_PRODUCT_HORI_HORIPAD_PRO_SERIES_X = 0x014f;
    private static final int USB_PRODUCT_RAZER_WOLVERINE_V2 = 0x0a29;
    private static final int USB_PRODUCT_RAZER_WOLVERINE_V2_CHROMA = 0x0a2e;
    private static final int USB_PRODUCT_TURTLE_BEACH_SERIES_X_REACT_R = 0x7013;
    private static final int USB_PRODUCT_TURTLE_BEACH_SERIES_X_RECON = 0x7009;
    private static final int USB_PRODUCT_THRUSTMASTER_ESWAPX_PRO = 0xd012;
    private static final int USB_PRODUCT_8BITDO_XBOX_CONTROLLER = 0x2002;
    private static final int USB_PRODUCT_GAMESIR_G7 = 0x1001;

    // Product IDs - Sony
    private static final int USB_PRODUCT_SONY_DS5_EDGE = 0x0df2;

    // Controller types (matches MoonBridge constants)
    public static final byte LI_CTYPE_UNKNOWN = 0x00;
    public static final byte LI_CTYPE_XBOX = 0x01;
    public static final byte LI_CTYPE_PS = 0x02;
    public static final byte LI_CTYPE_NINTENDO = 0x03;

    /**
     * Guess the controller type from vendor and product ID.
     *
     * @param vendorId USB vendor ID
     * @param productId USB product ID
     * @return Controller type constant (LI_CTYPE_*)
     */
    public static byte guessControllerType(int vendorId, int productId) {
        // Check by vendor ID first (covers most common cases)
        switch (vendorId) {
            case USB_VENDOR_MICROSOFT:
                return LI_CTYPE_XBOX;
            case USB_VENDOR_SONY:
                return LI_CTYPE_PS;
            case USB_VENDOR_NINTENDO:
                return LI_CTYPE_NINTENDO;
        }

        // Known controller database lookup using combined device ID
        int deviceId = makeControllerId(vendorId, productId);

        // PS Controllers
        if (deviceId == makeControllerId(0x054c, 0x0268) || // PS3
            deviceId == makeControllerId(0x054c, 0x05c4) || // PS4
            deviceId == makeControllerId(0x054c, 0x09cc) || // PS4 v2
            deviceId == makeControllerId(0x054c, 0x0ba0) || // PS4 Wireless
            deviceId == makeControllerId(0x054c, 0x0ce6) || // PS5
            deviceId == makeControllerId(0x054c, 0x0df2)) { // PS5 Edge
            return LI_CTYPE_PS;
        }

        // Xbox Controllers
        if (deviceId == makeControllerId(0x045e, 0x028e) || // Xbox 360
            deviceId == makeControllerId(0x045e, 0x028f) || // Xbox 360
            deviceId == makeControllerId(0x045e, 0x0719) || // Xbox 360 Wireless
            deviceId == makeControllerId(0x045e, 0x02dd) || // Xbox One
            deviceId == makeControllerId(0x045e, 0x02e3) || // Xbox One Elite
            deviceId == makeControllerId(0x045e, 0x02ea) || // Xbox One S
            deviceId == makeControllerId(0x045e, 0x0b00) || // Xbox One Elite 2
            deviceId == makeControllerId(0x045e, 0x0b12)) { // Xbox Series X
            return LI_CTYPE_XBOX;
        }

        // Nintendo Controllers
        if (deviceId == makeControllerId(0x057e, 0x2006) || // Joy-Con Left
            deviceId == makeControllerId(0x057e, 0x2007) || // Joy-Con Right
            deviceId == makeControllerId(0x057e, 0x2009)) { // Pro Controller
            return LI_CTYPE_NINTENDO;
        }

        return LI_CTYPE_UNKNOWN;
    }

    /**
     * Check if controller has paddles (Xbox Elite or DualSense Edge).
     *
     * @param vendorId USB vendor ID
     * @param productId USB product ID
     * @return true if controller has paddles
     */
    public static boolean guessControllerHasPaddles(int vendorId, int productId) {
        return isXboxOneElite(vendorId, productId) || isDualSenseEdge(vendorId, productId);
    }

    /**
     * Check if controller has share button (Xbox Series X controllers).
     *
     * @param vendorId USB vendor ID
     * @param productId USB product ID
     * @return true if controller has share button
     */
    public static boolean guessControllerHasShareButton(int vendorId, int productId) {
        return isXboxSeriesX(vendorId, productId);
    }

    /**
     * Check if joystick is Xbox One Elite controller.
     */
    private static boolean isXboxOneElite(int vendorId, int productId) {
        if (vendorId == USB_VENDOR_MICROSOFT) {
            return productId == USB_PRODUCT_XBOX_ONE_ELITE_SERIES_1 ||
                   productId == USB_PRODUCT_XBOX_ONE_ELITE_SERIES_2 ||
                   productId == USB_PRODUCT_XBOX_ONE_ELITE_SERIES_2_BLUETOOTH ||
                   productId == USB_PRODUCT_XBOX_ONE_ELITE_SERIES_2_BLE;
        }
        return false;
    }

    /**
     * Check if joystick is Xbox Series X controller.
     */
    private static boolean isXboxSeriesX(int vendorId, int productId) {
        switch (vendorId) {
            case USB_VENDOR_MICROSOFT:
                return productId == USB_PRODUCT_XBOX_SERIES_X ||
                       productId == USB_PRODUCT_XBOX_SERIES_X_BLE;

            case USB_VENDOR_PDP:
                return productId == USB_PRODUCT_XBOX_SERIES_X_VICTRIX_GAMBIT ||
                       productId == USB_PRODUCT_XBOX_SERIES_X_PDP_BLUE ||
                       productId == USB_PRODUCT_XBOX_SERIES_X_PDP_AFTERGLOW;

            case USB_VENDOR_POWERA_ALT:
                return (productId >= 0x2001 && productId <= 0x201a) ||
                       productId == USB_PRODUCT_XBOX_SERIES_X_POWERA_FUSION_PRO2 ||
                       productId == USB_PRODUCT_XBOX_SERIES_X_POWERA_MOGA_XP_ULTRA ||
                       productId == USB_PRODUCT_XBOX_SERIES_X_POWERA_SPECTRA;

            case USB_VENDOR_HORI:
                return productId == USB_PRODUCT_HORI_FIGHTING_COMMANDER_OCTA_SERIES_X ||
                       productId == USB_PRODUCT_HORI_HORIPAD_PRO_SERIES_X;

            case USB_VENDOR_RAZER:
                return productId == USB_PRODUCT_RAZER_WOLVERINE_V2 ||
                       productId == USB_PRODUCT_RAZER_WOLVERINE_V2_CHROMA;

            case USB_VENDOR_THRUSTMASTER:
                return productId == USB_PRODUCT_THRUSTMASTER_ESWAPX_PRO;

            case USB_VENDOR_TURTLE_BEACH:
                return productId == USB_PRODUCT_TURTLE_BEACH_SERIES_X_REACT_R ||
                       productId == USB_PRODUCT_TURTLE_BEACH_SERIES_X_RECON;

            case USB_VENDOR_8BITDO:
                return productId == USB_PRODUCT_8BITDO_XBOX_CONTROLLER;

            case USB_VENDOR_GAMESIR:
                return productId == USB_PRODUCT_GAMESIR_G7;

            default:
                return false;
        }
    }

    /**
     * Check if joystick is DualSense Edge.
     */
    private static boolean isDualSenseEdge(int vendorId, int productId) {
        return vendorId == USB_VENDOR_SONY && productId == USB_PRODUCT_SONY_DS5_EDGE;
    }

    /**
     * Create a combined device ID from vendor and product ID.
     */
    private static int makeControllerId(int vendorId, int productId) {
        return (vendorId << 16) | (productId & 0xFFFF);
    }
}

