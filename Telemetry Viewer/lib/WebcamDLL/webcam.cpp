/**
 * A wrapper for accessing video input devices (webcams) on Windows using the DirectShow API.
 * This software is free and open source.
 * Written by Farrell Farahbod.
 * 
 * Documentation for DirectShow and related APIs:
 * https://docs.microsoft.com/en-us/windows/win32/directshow/
 * https://docs.microsoft.com/en-us/windows/win32/directshow/video-capture
 * https://docs.microsoft.com/en-us/windows/win32/directshow/configure-the-video-quality
 * https://docs.microsoft.com/en-us/windows/win32/directshow/configure-the-video-output-format
 * https://docs.microsoft.com/en-us/windows/win32/directshow/using-the-sample-grabber
 * https://docs.microsoft.com/en-us/windows/win32/com/processes--threads--and-apartments
 * https://docs.microsoft.com/en-us/windows/win32/desktop-programming
 * 
 * This project was created in Visual Studio 2022 Community Edition with:
 * 
 *     File > New > Project > Dynamic-Link Library > Next > Project Name = "WebcamDLL"
 *                                                          Location = "...Telemetry Viewer\lib\"
 *                                                          check "Place solution and project in same directory" > Create
 *     Project > WebcamDLL Properties > Configuration = "All Configurations" > C/C++ > Precompiled Headers > Precompiled Header = "Not using precompiled headers" > OK
 *     Solution Explorer > Source Files > right-click "pch.cpp"     > Remove > Delete
 *                         Header Files > right-click "pch.h"       > Remove > Delete
 *                                        right-click "framework.h" > Remove > Delete
 *     Edit dllmain.cpp:
 *         Remove line: #include "pch.h"
 *         Add lines:   #define WIN32_LEAN_AND_MEAN
 *                      #include <windows.h>
 *     Solution Explorer > right-click "Source Files" > Add > New Item > "webcam.cpp"
 *                         right-click "Header Files" > Add > New Item > "webcam.h"
 * 
 * To debug this DLL:
 * 
 *     In Eclipse:
 *         Ensure the Java code loads the *debug* build of this DLL by editing the call to System.load() in Webcam.java
 *         Compile the Java project into a jar file.
 *     In Visual Studio:
 *         Choose the "Debug" (not "Release") solution configuration.
 *         Right-click the project > Properties > Debugging > Command = "C:\Program Files\Java\jdk-25\bin\java.exe" (or wherever your java.exe is)
 *                                                            Command Arguments = "--enable-native-access=ALL-UNNAMED -jar TelemetryViewer.jar" (or whatever the jar file is called, and any args it needs)
 *                                                            Working Directory = "..\..\" (or wherever the jar is)
 *         Right-click the project > Set as Startup Project
 *         Place your breakpoints.
 *         Debug by clicking the "Local Windows Debugger" button.
 *         You'll get some exceptions, just check the "ignore these" checkbox.
 *         After a few seconds it should hit your breakpoint, assuming the Java code has called that function.
 */

#include "webcam.h"
#include <dshow.h>
#pragma comment(lib, "Strmiids.lib")
#pragma comment(lib, "Quartz.lib")

/*
 * Helper function that releases a MediaType if it was acquired.
 */
static void DeleteMediaType(AM_MEDIA_TYPE** mt) {
    if(*mt) {
        if((**mt).cbFormat != 0) {
            CoTaskMemFree((PVOID)(**mt).pbFormat);
            (**mt).cbFormat = 0;
            (**mt).pbFormat = NULL;
        }
        if((**mt).pUnk != NULL) {
            // pUnk should not be used.
            (**mt).pUnk->Release();
            (**mt).pUnk = NULL;
        }
        CoTaskMemFree(*mt);
        *mt = NULL;
    }
}

/*
 * Helper function that releases an object if it was acquired.
 */
template <class T> static void SafeRelease(T** thing) {
    if(*thing) {
        (*thing)->Release();
        *thing = NULL;
    }
}

// the SampleGrabber interface is deprecated, so it is no longer in the public headers
// these definitions are from old headers
static const IID   IID_ISampleGrabber   = { 0x6B652FFF, 0x11FE, 0x4fce,{ 0x92, 0xAD, 0x02, 0x66, 0xB5, 0xD7, 0xC7, 0x8F } };
static const IID   IID_ISampleGrabberCB = { 0x0579154A, 0x2B53, 0x4994,{ 0xB0, 0xD0, 0xE7, 0x73, 0x14, 0x8E, 0xFF, 0x85 } };
static const CLSID CLSID_SampleGrabber  = { 0xC1F400A0, 0x3F08, 0x11d3,{ 0x9F, 0x0B, 0x00, 0x60, 0x08, 0x03, 0x9E, 0x37 } };
static const CLSID CLSID_NullRenderer   = { 0xC1F400A4, 0x3F08, 0x11d3,{ 0x9F, 0x0B, 0x00, 0x60, 0x08, 0x03, 0x9E, 0x37 } };
DEFINE_GUID(IID_ISampleGrabber,   0x6B652FFF, 0x11FE, 0x4fce, 0x92, 0xAD, 0x02, 0x66, 0xB5, 0xD7, 0xC7, 0x8F);
DEFINE_GUID(IID_ISampleGrabberCB, 0x0579154A, 0x2B53, 0x4994, 0xB0, 0xD0, 0xE7, 0x73, 0x14, 0x8E, 0xFF, 0x85);
interface ISampleGrabberCB : public IUnknown {
    virtual STDMETHODIMP SampleCB(double SampleTime, IMediaSample* pSample) = 0;
    virtual STDMETHODIMP BufferCB(double SampleTime, BYTE* pBuffer, long BufferLen) = 0;
};
interface ISampleGrabber : public IUnknown {
    virtual HRESULT STDMETHODCALLTYPE SetOneShot(BOOL OneShot) = 0;
    virtual HRESULT STDMETHODCALLTYPE SetMediaType(const AM_MEDIA_TYPE* pType) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetConnectedMediaType(AM_MEDIA_TYPE* pType) = 0;
    virtual HRESULT STDMETHODCALLTYPE SetBufferSamples(BOOL BufferThem) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetCurrentBuffer(long* pBufferSize, long* pBuffer) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetCurrentSample(IMediaSample** ppSample) = 0;
    virtual HRESULT STDMETHODCALLTYPE SetCallback(ISampleGrabberCB* pCallback, long WhichMethodToCallback) = 0;
};

/*
 * When connecting to a camera, an object of this class will be given to DirectShow as the callback for recieving frames.
 */
class CSampleGrabberCB : public ISampleGrabberCB {

private:

    void (*handler)(uint8_t* buffer, int32_t bufferByteCount, int32_t width, int32_t heigth);
    int32_t width;
    int32_t height;

public:

    CSampleGrabberCB(void (*handler)(uint8_t* buffer, int32_t bufferByteCount, int32_t width, int32_t heigth), int32_t actualWidth, int32_t actualHeight) {
        this->handler = handler;
        this->width = actualWidth;
        this->height = actualHeight;
    }
    
    STDMETHODIMP BufferCB(double sampleTime, BYTE* buffer, long bufferByteCount) {
        handler(buffer, bufferByteCount, width, height);
        return 0;
    }

    // only implementing the SampleGrabber interface
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) {
        if(ppv == nullptr) {
            return E_POINTER;
        } else if(riid == IID_ISampleGrabberCB || riid == IID_IUnknown) {
            *ppv = (void *) static_cast<ISampleGrabberCB*>(this);
            return NOERROR;
        } else {
            return E_NOINTERFACE;
        }
    }

    // not implementing COM reference counting or the MediaSample callback
    STDMETHODIMP_(ULONG) AddRef()                                          { return 2; }
    STDMETHODIMP_(ULONG) Release()                                         { return 1; }
    STDMETHODIMP         SampleCB(double sampleTime, IMediaSample* sample) { return 0; }

};

/*
 * Helper function that appends a message to the log, if logging is enabled.
 * 
 * @param logPtr     Pointer to a log[], or null if logging is disabled.
 * @param logEnd     Pointer to the end of the log.
 * @param message    Line of text to append to the log. logPtr will be incremented accordingly.
 */
static void audit(wchar_t** logPtr, wchar_t* logEnd, const wchar_t* message) {

    const int64_t MAX_NEW_WCHARS = (logEnd - *logPtr);

    if(*logPtr != nullptr && MAX_NEW_WCHARS > 0) {
        int newWchars = swprintf(*logPtr, MAX_NEW_WCHARS, L"%s\n", message);
        if(newWchars > 0)
            *logPtr += newWchars;
        else
            *logPtr = logEnd; // log is full
    }

}

/*
 * Helper function that appends a message to the log, if logging is enabled, and ensures a function returned successfully.
 * An exception will be thrown if the function's HRESULT does not correspond to a success.
 *
 * @param logPtr     Pointer to a log[], or null if logging is disabled.
 * @param logEnd     Pointer to the end of the log.
 * @param message    Line of text to append to the log. logPtr will be incremented accordingly.
 * @param result     Return value of a function, which will be checked for success.
 */
static void audit(wchar_t** logPtr, wchar_t* logEnd, const wchar_t* message, HRESULT result) {

    const int64_t MAX_NEW_WCHARS = (logEnd - *logPtr);

    if(SUCCEEDED(result)) {
        if(*logPtr != nullptr && MAX_NEW_WCHARS > 0) {
            int newWchars = swprintf(*logPtr, MAX_NEW_WCHARS, L"[SUCCESS] %s\n", message);
            if(newWchars > 0)
                *logPtr += newWchars;
            else
                *logPtr = logEnd; // log is full
        }
    } else {
        if(*logPtr != nullptr && MAX_NEW_WCHARS > 0) {
            wchar_t errorAsText[MAX_ERROR_TEXT_LEN] = {0};
            AMGetErrorTextW(result, errorAsText, MAX_ERROR_TEXT_LEN);
            int newWchars = swprintf(*logPtr, MAX_NEW_WCHARS, L"[FAILURE] %s, HRESULT = %ld = %s\n", message, result, errorAsText);
            if(newWchars > 0)
                *logPtr += newWchars;
            else
                *logPtr = logEnd; // log is full
        }
        throw -1;
    }

}

/*
 * Gets information on all of the cameras present on this device.
 * 
 * @param   cameras           An array of Camera structs, that you initialized to all zeros, which will be populated with details.
 * @param   maxCameraCount    Number of elements in the array.
 * @param   log               Pointer to a wchar_t string, which will be filled with a log of technical details. Can be null.
 * @param   logByteCount      Size of the log, in bytes.
 * @returns                   The number of cameras found, clipped to maxCameraCount.
 *                            Note that cameras may be marked as invalid (cameras[n].valid == false) so the number of *usable* cameras may be less than this return value.
 *                            For example: if OBS is installed, there will be an "OBS Virtual Camera" but it is invalid and attempting to connect to it will fail.
 */
extern "C" __declspec(dllexport) int32_t getCameras(Camera cameras[], uint32_t maxCameraCount, wchar_t* log, int64_t logByteCount) {

    wchar_t* logEnd = log + (logByteCount / sizeof(wchar_t));
    int32_t cameraCount = 0;

    ICaptureGraphBuilder2* builder = NULL;
    IGraphBuilder* graph = NULL;
    ICreateDevEnum* deviceEnumerator = NULL;
    IEnumMoniker* videoInputsEnumerator = NULL;
    IMoniker* deviceMoniker = NULL;
    IPropertyBag* properties = NULL;
    IBaseFilter* videoFilter = NULL;
    IAMCameraControl* cameraControl = NULL;
    IAMVideoProcAmp* videoProcessor = NULL;
    IAMStreamConfig* streamConfig = NULL;
    AM_MEDIA_TYPE* mediaType = NULL;
    VARIANT variant;
    VariantInit(&variant);

    try {

        audit(&log, logEnd, L">>> Log for getCameras() <<<");
        audit(&log, logEnd, L"Initializing the COM library",               CoInitialize(NULL));
        audit(&log, logEnd, L"Creating the Filter Graph",                  CoCreateInstance(CLSID_FilterGraph, 0, CLSCTX_INPROC_SERVER, IID_IGraphBuilder, (void**) &graph));
        audit(&log, logEnd, L"Creating the Capture Graph Builder",         CoCreateInstance(CLSID_CaptureGraphBuilder2, NULL, CLSCTX_INPROC_SERVER, IID_ICaptureGraphBuilder2, (void**) &builder));
        audit(&log, logEnd, L"Setting the Builder's Filter Graph",         builder->SetFiltergraph(graph));
        audit(&log, logEnd, L"Creating the System Device Enumerator",      CoCreateInstance(CLSID_SystemDeviceEnum, NULL, CLSCTX_INPROC_SERVER, IID_ICreateDevEnum, (void**) &deviceEnumerator));
        audit(&log, logEnd, L"Creating the Video Input Device Enumerator", deviceEnumerator->CreateClassEnumerator(CLSID_VideoInputDeviceCategory, &videoInputsEnumerator, 0));

        // if no cameras are present, CreateClassEnumerator will "succeed" and return S_FALSE, but videoInputsEnumerator will remain NULL
        if(videoInputsEnumerator == NULL)
            throw -1;

        while(videoInputsEnumerator->Next(1, &deviceMoniker, NULL) == S_OK) {

            try {

                if(cameraCount == maxCameraCount)
                    break;

                audit(&log, logEnd, L"Enumerating a Device...");
                audit(&log, logEnd, L"Accessing the Property Bag", deviceMoniker->BindToStorage(0, 0, IID_IPropertyBag, (void**) &properties));

                VariantInit(&variant);
                audit(&log, logEnd, L"Reading the Friendly Name", properties->Read(L"FriendlyName", &variant, 0));
                wcscpy_s(cameras[cameraCount].friendlyName, MAX_STRING_LENGTH, variant.bstrVal);
                VariantClear(&variant);

                VariantInit(&variant);
                audit(&log, logEnd, L"Reading the Device Path", properties->Read(L"DevicePath", &variant, 0));
                wcscpy_s(cameras[cameraCount].devicePath, MAX_STRING_LENGTH, variant.bstrVal);
                VariantClear(&variant);

                audit(&log, logEnd, L"Getting the Base Filter",              deviceMoniker->BindToObject(0, 0, IID_IBaseFilter, (void**) &videoFilter));
                audit(&log, logEnd, L"Getting the Camera Control interface", videoFilter->QueryInterface(IID_IAMCameraControl, (void**) &cameraControl));

                long minimumValue = 0;
                long maximumValue = 0;
                long stepSize = 0;
                long defaultValue = 0;
                long flags = 0;

                if(cameraControl->GetRange(CameraControlProperty::CameraControl_Pan, &minimumValue, &maximumValue, &stepSize, &defaultValue, &flags) == S_OK) {
                    cameras[cameraCount].panSupported        = true;
                    cameras[cameraCount].panMinimum          = minimumValue;
                    cameras[cameraCount].panMaximum          = maximumValue;
                    cameras[cameraCount].panDefault          = defaultValue;
                    cameras[cameraCount].panStepSize         = stepSize;
                    cameras[cameraCount].panAutomaticAllowed = flags & CameraControlFlags::CameraControl_Flags_Auto;
                    cameras[cameraCount].panManualAllowed    = flags & CameraControlFlags::CameraControl_Flags_Manual;
                }

                if(cameraControl->GetRange(CameraControlProperty::CameraControl_Tilt, &minimumValue, &maximumValue, &stepSize, &defaultValue, &flags) == S_OK) {
                    cameras[cameraCount].tiltSupported        = true;
                    cameras[cameraCount].tiltMinimum          = minimumValue;
                    cameras[cameraCount].tiltMaximum          = maximumValue;
                    cameras[cameraCount].tiltDefault          = defaultValue;
                    cameras[cameraCount].tiltStepSize         = stepSize;
                    cameras[cameraCount].tiltAutomaticAllowed = flags & CameraControlFlags::CameraControl_Flags_Auto;
                    cameras[cameraCount].tiltManualAllowed    = flags & CameraControlFlags::CameraControl_Flags_Manual;
                }

                if(cameraControl->GetRange(CameraControlProperty::CameraControl_Roll, &minimumValue, &maximumValue, &stepSize, &defaultValue, &flags) == S_OK) {
                    cameras[cameraCount].rollSupported        = true;
                    cameras[cameraCount].rollMinimum          = minimumValue;
                    cameras[cameraCount].rollMaximum          = maximumValue;
                    cameras[cameraCount].rollDefault          = defaultValue;
                    cameras[cameraCount].rollStepSize         = stepSize;
                    cameras[cameraCount].rollAutomaticAllowed = flags & CameraControlFlags::CameraControl_Flags_Auto;
                    cameras[cameraCount].rollManualAllowed    = flags & CameraControlFlags::CameraControl_Flags_Manual;
                }

                if(cameraControl->GetRange(CameraControlProperty::CameraControl_Zoom, &minimumValue, &maximumValue, &stepSize, &defaultValue, &flags) == S_OK) {
                    cameras[cameraCount].zoomSupported        = true;
                    cameras[cameraCount].zoomMinimum          = minimumValue;
                    cameras[cameraCount].zoomMaximum          = maximumValue;
                    cameras[cameraCount].zoomDefault          = defaultValue;
                    cameras[cameraCount].zoomStepSize         = stepSize;
                    cameras[cameraCount].zoomAutomaticAllowed = flags & CameraControlFlags::CameraControl_Flags_Auto;
                    cameras[cameraCount].zoomManualAllowed    = flags & CameraControlFlags::CameraControl_Flags_Manual;
                }

                if(cameraControl->GetRange(CameraControlProperty::CameraControl_Exposure, &minimumValue, &maximumValue, &stepSize, &defaultValue, &flags) == S_OK) {
                    cameras[cameraCount].exposureSupported        = true;
                    cameras[cameraCount].exposureMinimum          = minimumValue;
                    cameras[cameraCount].exposureMaximum          = maximumValue;
                    cameras[cameraCount].exposureDefault          = defaultValue;
                    cameras[cameraCount].exposureStepSize         = stepSize;
                    cameras[cameraCount].exposureAutomaticAllowed = flags & CameraControlFlags::CameraControl_Flags_Auto;
                    cameras[cameraCount].exposureManualAllowed    = flags & CameraControlFlags::CameraControl_Flags_Manual;
                }

                if(cameraControl->GetRange(CameraControlProperty::CameraControl_Iris, &minimumValue, &maximumValue, &stepSize, &defaultValue, &flags) == S_OK) {
                    cameras[cameraCount].irisSupported        = true;
                    cameras[cameraCount].irisMinimum          = minimumValue;
                    cameras[cameraCount].irisMaximum          = maximumValue;
                    cameras[cameraCount].irisDefault          = defaultValue;
                    cameras[cameraCount].irisStepSize         = stepSize;
                    cameras[cameraCount].irisAutomaticAllowed = flags & CameraControlFlags::CameraControl_Flags_Auto;
                    cameras[cameraCount].irisManualAllowed    = flags & CameraControlFlags::CameraControl_Flags_Manual;
                }

                if(cameraControl->GetRange(CameraControlProperty::CameraControl_Focus, &minimumValue, &maximumValue, &stepSize, &defaultValue, &flags) == S_OK) {
                    cameras[cameraCount].focusSupported        = true;
                    cameras[cameraCount].focusMinimum          = minimumValue;
                    cameras[cameraCount].focusMaximum          = maximumValue;
                    cameras[cameraCount].focusDefault          = defaultValue;
                    cameras[cameraCount].focusStepSize         = stepSize;
                    cameras[cameraCount].focusAutomaticAllowed = flags & CameraControlFlags::CameraControl_Flags_Auto;
                    cameras[cameraCount].focusManualAllowed    = flags & CameraControlFlags::CameraControl_Flags_Manual;
                }

                SafeRelease(&cameraControl);

                audit(&log, logEnd, L"Getting the Video Processor interface", videoFilter->QueryInterface(IID_IAMVideoProcAmp, (void**) &videoProcessor));

                if(videoProcessor->GetRange(VideoProcAmpProperty::VideoProcAmp_Brightness, &minimumValue, &maximumValue, &stepSize, &defaultValue, &flags) == S_OK) {
                    cameras[cameraCount].brightnessSupported        = true;
                    cameras[cameraCount].brightnessMinimum          = minimumValue;
                    cameras[cameraCount].brightnessMaximum          = maximumValue;
                    cameras[cameraCount].brightnessDefault          = defaultValue;
                    cameras[cameraCount].brightnessStepSize         = stepSize;
                    cameras[cameraCount].brightnessAutomaticAllowed = flags & VideoProcAmpFlags::VideoProcAmp_Flags_Auto;
                    cameras[cameraCount].brightnessManualAllowed    = flags & VideoProcAmpFlags::VideoProcAmp_Flags_Manual;
                }

                if(videoProcessor->GetRange(VideoProcAmpProperty::VideoProcAmp_Contrast, &minimumValue, &maximumValue, &stepSize, &defaultValue, &flags) == S_OK) {
                    cameras[cameraCount].contrastSupported        = true;
                    cameras[cameraCount].contrastMinimum          = minimumValue;
                    cameras[cameraCount].contrastMaximum          = maximumValue;
                    cameras[cameraCount].contrastDefault          = defaultValue;
                    cameras[cameraCount].contrastStepSize         = stepSize;
                    cameras[cameraCount].contrastAutomaticAllowed = flags & VideoProcAmpFlags::VideoProcAmp_Flags_Auto;
                    cameras[cameraCount].contrastManualAllowed    = flags & VideoProcAmpFlags::VideoProcAmp_Flags_Manual;
                }

                if(videoProcessor->GetRange(VideoProcAmpProperty::VideoProcAmp_Hue, &minimumValue, &maximumValue, &stepSize, &defaultValue, &flags) == S_OK) {
                    cameras[cameraCount].hueSupported        = true;
                    cameras[cameraCount].hueMinimum          = minimumValue;
                    cameras[cameraCount].hueMaximum          = maximumValue;
                    cameras[cameraCount].hueDefault          = defaultValue;
                    cameras[cameraCount].hueStepSize         = stepSize;
                    cameras[cameraCount].hueAutomaticAllowed = flags & VideoProcAmpFlags::VideoProcAmp_Flags_Auto;
                    cameras[cameraCount].hueManualAllowed    = flags & VideoProcAmpFlags::VideoProcAmp_Flags_Manual;
                }

                if(videoProcessor->GetRange(VideoProcAmpProperty::VideoProcAmp_Saturation, &minimumValue, &maximumValue, &stepSize, &defaultValue, &flags) == S_OK) {
                    cameras[cameraCount].saturationSupported        = true;
                    cameras[cameraCount].saturationMinimum          = minimumValue;
                    cameras[cameraCount].saturationMaximum          = maximumValue;
                    cameras[cameraCount].saturationDefault          = defaultValue;
                    cameras[cameraCount].saturationStepSize         = stepSize;
                    cameras[cameraCount].saturationAutomaticAllowed = flags & VideoProcAmpFlags::VideoProcAmp_Flags_Auto;
                    cameras[cameraCount].saturationManualAllowed    = flags & VideoProcAmpFlags::VideoProcAmp_Flags_Manual;
                }

                if(videoProcessor->GetRange(VideoProcAmpProperty::VideoProcAmp_Sharpness, &minimumValue, &maximumValue, &stepSize, &defaultValue, &flags) == S_OK) {
                    cameras[cameraCount].sharpnessSupported        = true;
                    cameras[cameraCount].sharpnessMinimum          = minimumValue;
                    cameras[cameraCount].sharpnessMaximum          = maximumValue;
                    cameras[cameraCount].sharpnessDefault          = defaultValue;
                    cameras[cameraCount].sharpnessStepSize         = stepSize;
                    cameras[cameraCount].sharpnessAutomaticAllowed = flags & VideoProcAmpFlags::VideoProcAmp_Flags_Auto;
                    cameras[cameraCount].sharpnessManualAllowed    = flags & VideoProcAmpFlags::VideoProcAmp_Flags_Manual;
                }

                if(videoProcessor->GetRange(VideoProcAmpProperty::VideoProcAmp_Gamma, &minimumValue, &maximumValue, &stepSize, &defaultValue, &flags) == S_OK) {
                    cameras[cameraCount].gammaSupported        = true;
                    cameras[cameraCount].gammaMinimum          = minimumValue;
                    cameras[cameraCount].gammaMaximum          = maximumValue;
                    cameras[cameraCount].gammaDefault          = defaultValue;
                    cameras[cameraCount].gammaStepSize         = stepSize;
                    cameras[cameraCount].gammaAutomaticAllowed = flags & VideoProcAmpFlags::VideoProcAmp_Flags_Auto;
                    cameras[cameraCount].gammaManualAllowed    = flags & VideoProcAmpFlags::VideoProcAmp_Flags_Manual;
                }

                if(videoProcessor->GetRange(VideoProcAmpProperty::VideoProcAmp_ColorEnable, &minimumValue, &maximumValue, &stepSize, &defaultValue, &flags) == S_OK) {
                    cameras[cameraCount].colorSupported = true;
                    cameras[cameraCount].colorDefault   = defaultValue;
                }

                if(videoProcessor->GetRange(VideoProcAmpProperty::VideoProcAmp_WhiteBalance, &minimumValue, &maximumValue, &stepSize, &defaultValue, &flags) == S_OK) {
                    cameras[cameraCount].whiteBalanceSupported        = true;
                    cameras[cameraCount].whiteBalanceMinimum          = minimumValue;
                    cameras[cameraCount].whiteBalanceMaximum          = maximumValue;
                    cameras[cameraCount].whiteBalanceDefault          = defaultValue;
                    cameras[cameraCount].whiteBalanceStepSize         = stepSize;
                    cameras[cameraCount].whiteBalanceAutomaticAllowed = flags & VideoProcAmpFlags::VideoProcAmp_Flags_Auto;
                    cameras[cameraCount].whiteBalanceManualAllowed    = flags & VideoProcAmpFlags::VideoProcAmp_Flags_Manual;
                }

                if(videoProcessor->GetRange(VideoProcAmpProperty::VideoProcAmp_BacklightCompensation, &minimumValue, &maximumValue, &stepSize, &defaultValue, &flags) == S_OK) {
                    cameras[cameraCount].backlightCompensationSupported = true;
                    cameras[cameraCount].backlightCompensationDefault   = defaultValue;
                }

                if(videoProcessor->GetRange(VideoProcAmpProperty::VideoProcAmp_Gain, &minimumValue, &maximumValue, &stepSize, &defaultValue, &flags) == S_OK) {
                    cameras[cameraCount].gainSupported        = true;
                    cameras[cameraCount].gainMinimum          = minimumValue;
                    cameras[cameraCount].gainMaximum          = maximumValue;
                    cameras[cameraCount].gainDefault          = defaultValue;
                    cameras[cameraCount].gainStepSize         = stepSize;
                    cameras[cameraCount].gainAutomaticAllowed = flags & VideoProcAmpFlags::VideoProcAmp_Flags_Auto;
                    cameras[cameraCount].gainManualAllowed    = flags & VideoProcAmpFlags::VideoProcAmp_Flags_Manual;
                }

                SafeRelease(&videoProcessor);

                // some webcams don't have a StreamConfig interface, in that case we won't know the image resolution until after we connect
                // if the StreamConfig interface is not supported, camera.resolutionsCount will remain at 0
                try {
                    audit(&log, logEnd, L"Getting the Stream Configuration interface", builder->FindInterface(&PIN_CATEGORY_PREVIEW, &MEDIATYPE_Video, videoFilter, IID_IAMStreamConfig, (void**) &streamConfig));

                    int formatCount = 0;
                    int formatSize = 0;

                    audit(&log, logEnd, L"Getting the number of Stream Capabilities", streamConfig->GetNumberOfCapabilities(&formatCount, &formatSize));
                    if(formatSize != sizeof(VIDEO_STREAM_CONFIG_CAPS))
                        audit(&log, logEnd, L"Wrong data structure size", S_FALSE);

                    cameras[cameraCount].resolutionsCount = formatCount;
                    for(int formatN = 0; formatN < formatCount; formatN++) {

                        if(formatN == MAX_RESOLUTIONS_COUNT)
                            break;

                        VIDEO_STREAM_CONFIG_CAPS caps = { 0 };
                        audit(&log, logEnd, L"Getting a Stream Capability", streamConfig->GetStreamCaps(formatN, &mediaType, (BYTE*) &caps));
                        cameras[cameraCount].resolutionWidth[formatN]  = caps.MaxOutputSize.cx;
                        cameras[cameraCount].resolutionHeight[formatN] = caps.MaxOutputSize.cy;
                        DeleteMediaType(&mediaType);

                    }
                } catch (...) {
                    DeleteMediaType(&mediaType);
                    SafeRelease(&streamConfig);
                }

                SafeRelease(&streamConfig);
                SafeRelease(&videoFilter);
                SafeRelease(&properties);
                SafeRelease(&deviceMoniker);

                // success
                cameras[cameraCount].valid = true;

            } catch (...) {

                // this camera is invalid, skip it
                cameras[cameraCount].valid = false;

                VariantClear(&variant);
                DeleteMediaType(&mediaType);
                SafeRelease(&streamConfig);
                SafeRelease(&videoProcessor);
                SafeRelease(&cameraControl);
                SafeRelease(&videoFilter);
                SafeRelease(&properties);
                SafeRelease(&deviceMoniker);

            }

            cameraCount++;

        }

    } catch(...) {

        // aborting
        cameraCount = 0;

    }

    // free all resources
    DeleteMediaType(&mediaType);
    SafeRelease(&streamConfig);
    SafeRelease(&videoProcessor);
    SafeRelease(&cameraControl);
    SafeRelease(&videoFilter);
    SafeRelease(&properties);
    SafeRelease(&deviceMoniker);
    SafeRelease(&videoInputsEnumerator);
    SafeRelease(&deviceEnumerator);
    SafeRelease(&graph);
    SafeRelease(&builder);
    CoUninitialize();

    // done
    return cameraCount;

}

// a crude "map" from DevicePath -> (MediaControl, MediaEvent, CameraControl, VideoProcAmp)
static const int         MAX_CAMERA_COUNT = 16;
static wchar_t*          device[MAX_CAMERA_COUNT];
static IMediaControl*    controlForDevice[MAX_CAMERA_COUNT];
static IMediaEvent*      eventForDevice[MAX_CAMERA_COUNT];
static IAMCameraControl* cameraControlForDevice[MAX_CAMERA_COUNT];
static IAMVideoProcAmp*  videoProcessorForDevice[MAX_CAMERA_COUNT];

/*
 * Connects to a camera and starts receiving images.
 * 
 * @param devicePath      A devicePath       from getCameras().
 * @param width           A resolutionWidth  from getCameras(). If resolutionsCount was 0, this will be ignored.
 * @param height          A resolutionHeight from getCameras(). If resolutionsCount was 0, this will be ignored.
 * @param handler         Your "void handler(uint8_t* buffer, uint32_t bufferByteCount, int32_t width, int32_t height)" that will receive BGR24 images.
 * @param log             Pointer to a wchar_t string, which will be filled with a log of technical details. Can be null.
 * @param logByteCount    Size of the log, in bytes.
 * @returns               True on success, or false if an error occurred.
 */
extern "C" __declspec(dllexport) bool connectCamera(const wchar_t* devicePath, int32_t width, int32_t height, void (*handler)(uint8_t* buffer, int32_t bufferByteCount, int32_t width, int32_t heigth), wchar_t* log, int64_t logByteCount) {

    wchar_t*  logEnd = log + (logByteCount / sizeof(wchar_t));

    disconnectCamera(devicePath);

    bool success = false;
    
    IGraphBuilder* graph = NULL;
    ICaptureGraphBuilder2* builder = NULL;
    ICreateDevEnum* systemEnumerator = NULL;
    IEnumMoniker* videoInputsEnumerator = NULL;
    IMoniker* deviceMoniker = NULL;
    IPropertyBag* properties = NULL;
    IBaseFilter* videoFilter = NULL;
    IAMCameraControl* cameraControl = NULL;
    IAMVideoProcAmp* videoProcessor = NULL;
    IAMStreamConfig* streamConfig = NULL;
    AM_MEDIA_TYPE* mediaType = NULL;
    IBaseFilter* grabberFilter = NULL;
    ISampleGrabber* grabber = NULL;
    IBaseFilter* nullRenderer = NULL;
    IMediaFilter* mediaFilter = NULL;
    VARIANT variant;
    VariantInit(&variant);
    IMediaControl* control = NULL;
    IMediaEvent* event = NULL;

    try {

        audit(&log, logEnd, L">>> Log for connect() <<<");
        audit(&log, logEnd, L"Initializing the COM library",               CoInitialize(NULL));
        audit(&log, logEnd, L"Creating the Filter Graph",                  CoCreateInstance(CLSID_FilterGraph, 0, CLSCTX_INPROC_SERVER, IID_IGraphBuilder, (void**) &graph));
        audit(&log, logEnd, L"Creating the Capture Graph Builder",         CoCreateInstance(CLSID_CaptureGraphBuilder2, NULL, CLSCTX_INPROC_SERVER, IID_ICaptureGraphBuilder2, (void**) &builder));
        audit(&log, logEnd, L"Setting the Builder's Filter Graph",         builder->SetFiltergraph(graph));
        audit(&log, logEnd, L"Creating the System Device Enumerator",      CoCreateInstance(CLSID_SystemDeviceEnum, NULL, CLSCTX_INPROC_SERVER, IID_ICreateDevEnum, (void**) &systemEnumerator));
        audit(&log, logEnd, L"Creating the Video Input Device Enumerator", systemEnumerator->CreateClassEnumerator(CLSID_VideoInputDeviceCategory, &videoInputsEnumerator, 0));

        // if no cameras are present, CreateClassEnumerator will "succeed" and return S_FALSE, but videoInputsEnumerator will remain NULL
        if(videoInputsEnumerator == NULL)
            throw - 1;

        while(videoInputsEnumerator->Next(1, &deviceMoniker, NULL) == S_OK) {

            try {
                audit(&log, logEnd, L"Enumerating a Device...");
                audit(&log, logEnd, L"Accessing the Property Bag", deviceMoniker->BindToStorage(0, 0, IID_IPropertyBag, (void**) &properties));

                VariantInit(&variant);
                audit(&log, logEnd, L"Reading the Device Path", properties->Read(L"DevicePath", &variant, 0));
                SafeRelease(&properties);

                if(wcscmp(devicePath, variant.bstrVal) != 0) {
                    audit(&log, logEnd, L"Skipping this device, it is not the requested device", S_OK);
                    VariantClear(&variant);
                    continue;
                } else {
                    audit(&log, logEnd, L"Found the requested device", S_OK);
                    VariantClear(&variant);
                }

                audit(&log, logEnd, L"Getting the Base Filter", deviceMoniker->BindToObject(0, 0, IID_IBaseFilter, (void**) &videoFilter));
                SafeRelease(&deviceMoniker);

                audit(&log, logEnd, L"Adding the Base Filter to the graph",   graph->AddFilter(videoFilter, L"Capture Filter"));
                audit(&log, logEnd, L"Getting the Camera Control interface",  videoFilter->QueryInterface(IID_IAMCameraControl, (void**) &cameraControl));
                audit(&log, logEnd, L"Getting the Video Processor interface", videoFilter->QueryInterface(IID_IAMVideoProcAmp, (void**) &videoProcessor));

                // some webcams don't have a StreamConfig interface, in that case we won't know the resolution until after we connect
                try {
                    int formatCount = 0;
                    int formatSize = 0;
                    audit(&log, logEnd, L"Getting the Stream Configuration interface", builder->FindInterface(&PIN_CATEGORY_PREVIEW, &MEDIATYPE_Video, videoFilter, IID_IAMStreamConfig, (void**)&streamConfig));
                    audit(&log, logEnd, L"Getting the number of Stream Capabilities",  streamConfig->GetNumberOfCapabilities(&formatCount, &formatSize));

                    for (int formatN = 0; formatN < formatCount; formatN++) {
                        if (formatN == MAX_RESOLUTIONS_COUNT)
                            break;

                        VIDEO_STREAM_CONFIG_CAPS caps = { 0 };
                        audit(&log, logEnd, L"Getting a Stream Capability", streamConfig->GetStreamCaps(formatN, &mediaType, (BYTE*)&caps));

                        if(width == caps.MaxOutputSize.cx && height == caps.MaxOutputSize.cy) {
                            VIDEOINFOHEADER* vih = (VIDEOINFOHEADER*)(mediaType->pbFormat);
                            vih->AvgTimePerFrame = 1;
                            audit(&log, logEnd, L"Found the Stream Capability with the requested resolution, using it and requesting max FPS", streamConfig->SetFormat(mediaType));
                            DeleteMediaType(&mediaType);
                            break;
                        } else {
                            audit(&log, logEnd, L"Skipping this Stream Capability, it is not the requested one", S_OK);
                            DeleteMediaType(&mediaType);
                            continue;
                        }
                    }
                } catch (...) {
                    DeleteMediaType(&mediaType);
                    SafeRelease(&streamConfig);
                }

                audit(&log, logEnd, L"Creating the Sample Grabber filter",     CoCreateInstance(CLSID_SampleGrabber, 0, CLSCTX_INPROC_SERVER, IID_IBaseFilter, (void**) &grabberFilter));
                audit(&log, logEnd, L"Adding the Sample Grabber to the graph", graph->AddFilter(grabberFilter, L"Sample Grabber"));
                audit(&log, logEnd, L"Getting the Sample Grabber interface",   grabberFilter->QueryInterface(IID_ISampleGrabber, (void**) &grabber));

                AM_MEDIA_TYPE type = { 0 };
                type.majortype = MEDIATYPE_Video;
                type.subtype = MEDIASUBTYPE_RGB24;
                type.formattype = FORMAT_VideoInfo;

                audit(&log, logEnd, L"Setting the Sample Grabber's media type to RGB24 Video", grabber->SetMediaType(&type));
                audit(&log, logEnd, L"Setting the Sample Grabber to not buffer samples",       grabber->SetBufferSamples(FALSE));
                audit(&log, logEnd, L"Getting the Media Filter interface",                     graph->QueryInterface(IID_IMediaFilter, (void**) &mediaFilter));
                audit(&log, logEnd, L"Disabling the reference clock",                          mediaFilter->SetSyncSource(NULL));
                SafeRelease(&mediaFilter);
                audit(&log, logEnd, L"Creating the Null Renderer filter",     CoCreateInstance(CLSID_NullRenderer, 0, CLSCTX_INPROC_SERVER, IID_IBaseFilter, (void**) &nullRenderer));
                audit(&log, logEnd, L"Adding the Null Renderer to the graph", graph->AddFilter(nullRenderer, L"Null Renderer"));
                audit(&log, logEnd, L"Rendering the Stream",                  builder->RenderStream(&PIN_CATEGORY_PREVIEW, &MEDIATYPE_Video, videoFilter, grabberFilter, nullRenderer));

                audit(&log, logEnd, L"Getting the Sample Grabber's media type", grabber->GetConnectedMediaType(&type));
                VIDEOINFOHEADER* info = (VIDEOINFOHEADER*) type.pbFormat;
                int32_t actualWidth  = info->bmiHeader.biWidth;
                int32_t actualHeight = info->bmiHeader.biHeight;

                CSampleGrabberCB* CB = new CSampleGrabberCB(handler, actualWidth, actualHeight);
                audit(&log, logEnd, L"Setting the Sample Grabber's callback", grabber->SetCallback(CB, 1));
                audit(&log, logEnd, L"Getting the Media Control interface",   graph->QueryInterface(IID_IMediaControl, (void**) &control));
                audit(&log, logEnd, L"Getting the Media Event interface",     graph->QueryInterface(IID_IMediaEvent, (void**) &event));
                audit(&log, logEnd, L"Running the Graph",                     control->Run());

                // save the MediaControl/MediaEvent/CameraControl/VideoProcAmp interfaces for later use
                bool saved = false;
                for(int i = 0; i < MAX_CAMERA_COUNT; i++) {
                    if(device[i] == nullptr) {
                        size_t charsNeeded = wcslen(devicePath) + 1; // +1 for null terminator
                        wchar_t* devicePathCopy = (wchar_t*) malloc(charsNeeded * sizeof(wchar_t));
                        if(devicePathCopy != nullptr)
                            wcscpy_s(devicePathCopy, charsNeeded, devicePath);
                        device[i] = devicePathCopy;
                        controlForDevice[i] = control;
                        eventForDevice[i] = event;
                        cameraControlForDevice[i] = cameraControl;
                        videoProcessorForDevice[i] = videoProcessor;
                        saved = true;
                        break;
                    }
                }
                audit(&log, logEnd, L"Saving the MediaControl, MediaEvent, CameraControl and VideoProcAmp interfaces", saved ? 1 : -1);

                // done
                success = true;
                break;

            } catch(...) {
                
                // aborting
                VariantClear(&variant);
                success = false;
                break;

            }

        }

    } catch (...) {

        // aborting
        success = false;

    }

    // free resources
    if(success == false) {
        SafeRelease(&event);
        SafeRelease(&control);
        SafeRelease(&videoProcessor);
        SafeRelease(&cameraControl);
    }
    DeleteMediaType(&mediaType);
    SafeRelease(&mediaFilter);
    SafeRelease(&nullRenderer);
    SafeRelease(&grabber);
    SafeRelease(&grabberFilter);
    SafeRelease(&streamConfig);
    SafeRelease(&videoFilter);
    SafeRelease(&properties);
    SafeRelease(&deviceMoniker);
    SafeRelease(&videoInputsEnumerator);
    SafeRelease(&systemEnumerator);
    SafeRelease(&builder);
    SafeRelease(&graph);
    CoUninitialize();
    
    if(success) {
        try {
            // we always get event codes 13 and 14 as the first two events after running the graph, that is normal
            // if the connection is good, there should be no more events. if the camera is already in use, error code 3 ("error abort") will occur
            audit(&log, logEnd, L"Checking if the first event is as expected (code 13: clock changed)", checkForCameraEvent(devicePath) == 13 ? S_OK : E_ABORT);
            audit(&log, logEnd, L"Checking if the second event is as expected (code 14: paused)",       checkForCameraEvent(devicePath) == 14 ? S_OK : E_ABORT);
            audit(&log, logEnd, L"Checking if there is a third event (should not have a third event)",  checkForCameraEvent(devicePath) == 0  ? S_OK : E_ABORT);
        } catch(...) {
            disconnectCamera(devicePath);
            success = false;
        }
    }
    
    // done
    return success;

}

/*
 * Disconnects from a camera.
 * 
 * @param devicePath    A devicePath from getCameras().
 * @returns             True if the camera was connected, or false if the camera was not connected.
 */
extern "C" __declspec(dllexport) bool disconnectCamera(const wchar_t* devicePath) {

    for(int i = 0; i < MAX_CAMERA_COUNT; i++) {
        if(device[i] == nullptr) {
            // map slot is unused
            continue;
        } else if(wcscmp(devicePath, device[i]) == 0) {
            // found device in the map, disconnect and remove it from the map
            controlForDevice[i]->Stop();
            SafeRelease(&controlForDevice[i]);
            SafeRelease(&eventForDevice[i]);
            SafeRelease(&cameraControlForDevice[i]);
            SafeRelease(&videoProcessorForDevice[i]);
            free(device[i]);
            device[i] = nullptr;
            return true;
        }
    }

    // device is not in the map
    return false;

}

/*
 * Checks a camera for an event.
 * 
 * @param devicePath    A devicePath from getCameras().
 * @returns             -1 if the device is not connected.
 *                      0  if the device is connected and no event has occurred.
 *                      >0 if the device is connected and an event has occurred. The number will be an eventCode defined in <evcode.h>
 *                      See https://learn.microsoft.com/en-us/windows/win32/directshow/event-notification-codes
 */
extern "C" __declspec(dllexport) int32_t checkForCameraEvent(const wchar_t* devicePath) {

    for(int i = 0; i < MAX_CAMERA_COUNT; i++) {
        if(device[i] == nullptr) {
            // map slot is unused
            continue;
        } else if(wcscmp(devicePath, device[i]) == 0) {
            // device is in the map, query it
            long eventCode = 0;
            LONG_PTR param1 = 0;
            LONG_PTR param2 = 0;
            HRESULT hr = eventForDevice[i]->GetEvent(&eventCode, &param1, &param2, 0);
            if(hr == S_OK) {
                // got an event
                eventForDevice[i]->FreeEventParams(eventCode, param1, param2);
                return eventCode;
            } else {
                // no event
                return 0;
            }
        }
    }

    // device is not in the map
    return -1;

}

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
extern "C" __declspec(dllexport) bool setCameraSetting(const wchar_t* devicePath, int32_t interfaceEnum, int32_t settingEnum, bool isManual, int32_t manualValue) {

    for(int i = 0; i < MAX_CAMERA_COUNT; i++) {
        if(device[i] == nullptr) {
            // map slot is unused
            continue;
        } else if(wcscmp(devicePath, device[i]) == 0) {
            // device is in the map, adjust it
            if(interfaceEnum == 0)
                return cameraControlForDevice[i]->Set(settingEnum, manualValue, isManual ? CameraControl_Flags_Manual : CameraControl_Flags_Auto) == S_OK;
            else
                return videoProcessorForDevice[i]->Set(settingEnum, manualValue, isManual ? VideoProcAmp_Flags_Manual : VideoProcAmp_Flags_Auto) == S_OK;
        }
    }

    // device is not in the map
    return false;

}

/*
 * Gets a camera setting.
 * 
 * @param devicePath       A devicePath from getCameras().
 * @param interfaceEnum    0 = "CameraControl" interface, or 1 = "VideoProcAmp" interface.
 * @param settingEnum      The CameraControl or VideoProcAmp "Property" to read.
 * @returns                The low 32 bits contain the manualValue, and the high 32 bits contain the isManual boolean.
 *                         Or if an error occurred, -1 (all bits = 1) is returned.
 */
extern "C" __declspec(dllexport) int64_t getCameraSetting(const wchar_t* devicePath, int32_t interfaceEnum, int32_t settingEnum) {

    for(int i = 0; i < MAX_CAMERA_COUNT; i++) {
        if(device[i] == nullptr) {
            // map slot is unused
            continue;
        } else if(wcscmp(devicePath, device[i]) == 0) {
            // device is in the map, use it
            long value;
            long flags;
            HRESULT hr = (interfaceEnum == 0) ? cameraControlForDevice[i]->Get(settingEnum, &value, &flags) :
                                                videoProcessorForDevice[i]->Get(settingEnum, &value, &flags);
            return (hr == S_OK) ? (value | ((int64_t) flags << 32)) : -1;
        }
    }
    // device is not in the map
    return false;

}