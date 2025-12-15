#pragma once

#include <stdint.h>
#include <stdbool.h>

#define MAX_STRING_LENGTH 1024
#define MAX_CONFIGS_COUNT 32

struct camera {

    // if an error occured, this will be false
    bool valid;

    // from PropertyBag
    wchar_t friendlyName[MAX_STRING_LENGTH];
    wchar_t devicePath[MAX_STRING_LENGTH];

    // from CameraControl
    bool    panSupported;
    int32_t panMinimum;
    int32_t panMaximum;
    int32_t panDefault;
    int32_t panStepSize;
    bool    panAutomaticAllowed;
    bool    panManualAllowed;

    bool    tiltSupported;
    int32_t tiltMinimum;
    int32_t tiltMaximum;
    int32_t tiltDefault;
    int32_t tiltStepSize;
    bool    tiltAutomaticAllowed;
    bool    tiltManualAllowed;

    bool    rollSupported;
    int32_t rollMinimum;
    int32_t rollMaximum;
    int32_t rollDefault;
    int32_t rollStepSize;
    bool    rollAutomaticAllowed;
    bool    rollManualAllowed;

    bool    zoomSupported;
    int32_t zoomMinimum;
    int32_t zoomMaximum;
    int32_t zoomDefault;
    int32_t zoomStepSize;
    bool    zoomAutomaticAllowed;
    bool    zoomManualAllowed;

    bool    exposureSupported;
    int32_t exposureMinimum;
    int32_t exposureMaximum;
    int32_t exposureDefault;
    int32_t exposureStepSize;
    bool    exposureAutomaticAllowed;
    bool    exposureManualAllowed;

    bool    irisSupported;
    int32_t irisMinimum;
    int32_t irisMaximum;
    int32_t irisDefault;
    int32_t irisStepSize;
    bool    irisAutomaticAllowed;
    bool    irisManualAllowed;

    bool    focusSupported;
    int32_t focusMinimum;
    int32_t focusMaximum;
    int32_t focusDefault;
    int32_t focusStepSize;
    bool    focusAutomaticAllowed;
    bool    focusManualAllowed;

    // from VideoProcAmp
    bool    brightnessSupported;
    int32_t brightnessMinimum;
    int32_t brightnessMaximum;
    int32_t brightnessDefault;
    int32_t brightnessStepSize;
    bool    brightnessAutomaticAllowed;
    bool    brightnessManualAllowed;

    bool    contrastSupported;
    int32_t contrastMinimum;
    int32_t contrastMaximum;
    int32_t contrastDefault;
    int32_t contrastStepSize;
    bool    contrastAutomaticAllowed;
    bool    contrastManualAllowed;

    bool    hueSupported;
    int32_t hueMinimum;
    int32_t hueMaximum;
    int32_t hueDefault;
    int32_t hueStepSize;
    bool    hueAutomaticAllowed;
    bool    hueManualAllowed;

    bool    saturationSupported;
    int32_t saturationMinimum;
    int32_t saturationMaximum;
    int32_t saturationDefault;
    int32_t saturationStepSize;
    bool    saturationAutomaticAllowed;
    bool    saturationManualAllowed;

    bool    sharpnessSupported;
    int32_t sharpnessMinimum;
    int32_t sharpnessMaximum;
    int32_t sharpnessDefault;
    int32_t sharpnessStepSize;
    bool    sharpnessAutomaticAllowed;
    bool    sharpnessManualAllowed;

    bool    gammaSupported;
    int32_t gammaMinimum;
    int32_t gammaMaximum;
    int32_t gammaDefault;
    int32_t gammaStepSize;
    bool    gammaAutomaticAllowed;
    bool    gammaManualAllowed;

    bool    colorSupported;
    bool    colorDefault;

    bool    whiteBalanceSupported;
    int32_t whiteBalanceMinimum;
    int32_t whiteBalanceMaximum;
    int32_t whiteBalanceDefault;
    int32_t whiteBalanceStepSize;
    bool    whiteBalanceAutomaticAllowed;
    bool    whiteBalanceManualAllowed;

    bool    backlightCompensationSupported;
    bool    backlightCompensationDefault;

    bool    gainSupported;
    int32_t gainMinimum;
    int32_t gainMaximum;
    int32_t gainDefault;
    int32_t gainStepSize;
    bool    gainAutomaticAllowed;
    bool    gainManualAllowed;

    // from StreamConfig and MediaType
    int32_t configsCount;
    int32_t configHandle[MAX_CONFIGS_COUNT];      // MSBit = 0 for capture pin, or 1 for preview pin. Lower 31 bits = index for StreamConfig->GetStreamCaps()
    int32_t configWidth[MAX_CONFIGS_COUNT];       // pixels
    int32_t configHeight[MAX_CONFIGS_COUNT];      // pixels
    int64_t configMinInterval[MAX_CONFIGS_COUNT]; // 1 = 100ns
    int64_t configMaxInterval[MAX_CONFIGS_COUNT]; // 1 = 100ns
    int16_t configColorDepth[MAX_CONFIGS_COUNT];  // bits per pixel
    int32_t configFourCC[MAX_CONFIGS_COUNT];      // FourCC image type
};
typedef struct camera Camera;

#ifdef __cplusplus
extern "C" {
#endif

    /*
     * Gets information about all of the cameras present on this device.
     * 
     * @param   cameras           An array of Camera structs, that you initialized to all zeros, which will be populated with details.
     * @param   maxCameraCount    Number of elements in the array.
     * @param   log               Pointer to a wchar_t string, which will be filled with a log of technical details. Can be null.
     * @param   logByteCount      Size of the log, in bytes.
     * @returns                   The number of cameras found, clipped to maxCameraCount.
     *                            Note that cameras may be marked as invalid (cameras[n].valid == false) so the number of *usable* cameras may be less than this return value.
     *                            For example: if OBS is installed, there will be an "OBS Virtual Camera" but it is invalid and attempting to connect to it will fail.
     */
    __declspec(dllexport) int32_t getCameras(Camera cameras[], int32_t maxCameraCount, wchar_t* log, int64_t logByteCount);

    /*
     * Connects to a camera and starts receiving images.
     * 
     * A "configuration index" is used to select one of the resolution/color depth/FourCC combinations.
     * That configuration also has an allowed "frame interval" range, and a specific frame interval is used to select the desired FPS.
     * Note that the actual FPS may vary. For example, many cameras will reduce the FPS in low light conditions.
     * The frame interval has units of 100ns. Example: 166666 = 16666600ns = 60 FPS.
     * 
     * Images will be provided to an event handler. They will be JPEGs if the camera can provide JPEGs, otherwise they will be uncompressed BGR24.
     * The buffer provided to the event handler must be "used" before the handler returns. (Do not keep a pointer to the buffer.)
     * 
     * @param devicePath      A devicePath from getCameras().
     * @param configIndex     A configIndex from getCameras().
     * @param interval        A value between configMinInterval and configMaxInterval from getCameras().
     * @param handler         Your "void handler(uint8_t* buffer, int32_t bufferByteCount, int32_t width, int32_t height, bool isJpeg)" that will receive JPEG or BGR24 images.
     * @param log             Pointer to a wchar_t string, which will be filled with a log of technical details. Can be null.
     * @param logByteCount    Size of the log, in bytes.
     * @returns               True on success, or false if an error occurred.
     */
    __declspec(dllexport) bool connectCamera(const wchar_t* devicePath, int32_t configIndex, int64_t interval, void (*handler)(uint8_t* buffer, int32_t bufferByteCount, int32_t width, int32_t heigth, bool isJpeg), wchar_t* log, int64_t logByteCount);

    /*
     * Disconnects from a camera.
     * 
     * @param devicePath    A devicePath from getCameras().
     * @returns             True if the camera was connected, or false if the camera was not connected.
     */
    __declspec(dllexport) bool disconnectCamera(const wchar_t* devicePath);

    /*
     * Checks a camera for an event.
     * 
     * @param devicePath    A devicePath from getCameras().
     * @returns             -1 if the camera is not connected.
     *                      0  if the camera is connected and no event has occurred.
     *                      >0 if the camera is connected and an event has occurred. The number will be an eventCode defined in <evcode.h>
     *                      See https://learn.microsoft.com/en-us/windows/win32/directshow/event-notification-codes
     */
    __declspec(dllexport) int32_t checkForCameraEvent(const wchar_t* devicePath);

    /*
     * Adjusts a camera setting.
     * 
     * @param devicePath       A devicePath from getCameras().
     * @param interfaceEnum    0 = "CameraControl" interface, or 1 = "VideoProcAmp" interface.
     * @param settingEnum      The CameraControl or VideoProcAmp "Property" to adjust.
     * @param isManual         True to manually adjust this setting, or false to let the camera manage it automatically.
     * @param manualValue      The new value to use if isManual is true.
     * @returns                True on success, or false on error.
     */
    __declspec(dllexport) bool setCameraSetting(const wchar_t* devicePath, int32_t interfaceEnum, int32_t settingEnum, bool isManual, int32_t manualValue);

    /*
     * Gets a camera setting.
     * 
     * @param devicePath       A devicePath from getCameras().
     * @param interfaceEnum    0 = "CameraControl" interface, or 1 = "VideoProcAmp" interface.
     * @param settingEnum      The CameraControl or VideoProcAmp "Property" to read.
     * @returns                The low 32 bits contain the manualValue, and the high 32 bits contain the isManual boolean.
     *                         Or if an error occurred, -1 (all bits = 1) is returned.
     */
    __declspec(dllexport) int64_t getCameraSetting(const wchar_t* devicePath, int32_t interfaceEnum, int32_t settingEnum);

#ifdef __cplusplus
}
#endif