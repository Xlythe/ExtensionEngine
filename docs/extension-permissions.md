# Extension-owned operations over Binder

**Implementation and design.** A host can ask a trusted extension to perform a specific operation that Android permits the extension to perform under its own UID. For example, a host without camera permission could offer “Scan with Scanner Extension.” The extension shows the camera UI and returns the decoded barcode. The camera grant belongs to the extension throughout.

Today, `Extension.getExtension()` loads the extension class into the host process. Calling an Android API there uses the host's privileges, even when the class or its resources came from the extension APK. A participating extension publishes a bound service running under its own application UID. Existing Java calls and embedded views keep their current behavior. [Current loader](../extension-engine/src/main/java/com/xlythe/engine/extension/Extension.java) · [Android bound services](https://developer.android.com/develop/background-work/services/bound-services)

## Code in this PR

The library supplies [endpoint verification and binding](../extension-engine/src/main/java/com/xlythe/engine/extension/operations/ExtensionOperations.java), an [extension-owned permission activity](../extension-engine/src/main/java/com/xlythe/engine/extension/operations/OperationPermissionActivity.java), and [single-use approval tokens](../extension-engine/src/main/java/com/xlythe/engine/extension/operations/OperationApproval.java). Binding supports API 14+. The approval flow uses Binder extras on API 18+. Operations define their own typed IPC contract.

Two runnable APKs exercise these APIs on API 21+. The [host](../compatibility/src/previewHost/java/com/xlythe/test/preview/PreviewActivity.java) offers a result-only scan and a live preview. For a live preview, the extension confirms that the named host may receive camera images, obtains its own camera grant, then returns an approval. The host passes it and a texture-backed surface through [typed AIDL](../compatibility/src/preview/aidl/com/xlythe/test/preview/IPreviewV1.aidl). The [service](../compatibility/src/previewExtension/java/com/xlythe/test/preview/PreviewService.java) opens Camera2. For a result-only scan, a [private scanner activity](../compatibility/src/previewExtension/java/com/xlythe/test/preview/ScannerActivity.java) runs the decoder in the extension process and returns the text. The fixture launches this scanner full screen. Cross-app activity embedding is a possible presentation for a future host.

To wire up a provider, add `OPERATION_SERVICE` and `OPERATION_APPROVAL` metadata to the existing extension registration activity. Their values name components in the extension APK. Declare the supported host in the extension's package visibility queries. Configure reciprocal signer pins at startup for a separately signed pair. The [fixture manifest](../compatibility/src/previewExtension/AndroidManifest.xml) shows the full names and exported components. The library manifest adds no camera permission or exported operation component to consumers.

The host starts `ExtensionOperations.approvalIntent(activity, extensionPackage)` for result when the user selects the operation. After approval, it binds with `ExtensionOperations.bind(...)` and passes `OperationApproval.token(result)` through the operation's AIDL method. The extension calls `OperationApproval.consume(...)` on the incoming Binder thread before scheduling work. The live-preview binding opts into while-in-use capabilities. An ordinary binding uses the three-argument overload.

## Who chooses where a call runs?

The host invokes an explicit, typed feature such as `scanBarcode()`. An embedded extension view can ask the host to start that feature through the same interface. The host owns the routing policy and the UI for selecting a provider. Each operation declares its supported Android versions, local and remote implementations, required permissions, and whether execution needs a visible activity. The extension independently chooses which operations it offers to each host. The provider choice identifies the extension and the data the host will receive. Device or profile policy that disallows the requested operation ends the request.

| At the user's action | Routing decision |
| --- | --- |
| A local implementation exists and its prerequisites are satisfied | Execute locally. A user who explicitly selected an extension can use that provider instead. |
| The host lacks a prerequisite | Offer the configured, verified extension if it advertises that operation. A host permission denial alone never authorizes switching providers. A prior explicit provider choice can be reused for the same feature. |
| The selected extension has its prerequisites | Execute in the extension. Open its activity for operations that require visible UI, including the result-only scanner on devices that present it full screen. |
| The extension lacks a requestable runtime permission | Return an interaction request for that specific operation. Follow the permission flow below. |
| The operation is unsupported, policy blocks it, or a system call throws `SecurityException` | Return a typed failure. A permission check is a preflight. Android evaluates the actual call, including AppOps and while-in-use restrictions. Do not reroute or replay a failed call. |

Ordinary AOSP calls continue to execute where the calling class runs. Existing extension authors opt into the new method at the call site. The Binder contract carries versioned operation IDs and bounded, typed data. Class names, method names, framework managers and arbitrary intents are outside the wire format. The existing in-process reflection helper has no remote entry point. [Android AIDL](https://developer.android.com/develop/background-work/services/aidl)

## Trust and binding

Use the existing host-specific registration to locate the extension. The extension names its service and, if needed, its interaction activity in registration metadata. The host verifies package ownership of both components and applies the merged signing policy before binding by explicit component. Each side configures the exact peer packages eligible for each operation. Separately signed peers register reciprocal package-specific certificate pins in their own processes. Package visibility must let each side verify the other. An older extension without a compatible endpoint reports the feature as unavailable. [Merged signer policy](../extension-engine/src/main/java/com/xlythe/engine/extension/Security.java)

On every inbound Binder operation, including session methods, the extension checks the transaction's calling UID against the configured host package and its current signers before dispatch. Reject ambiguous shared UIDs for this protocol and bind requests and callbacks to the authenticated session. A caller-supplied package string, possession of a forwarded session Binder, or a matching certificate alone cannot authorize an operation. [Binder caller identity](https://developer.android.com/reference/android/os/Binder#getCallingUid())

Bind from the visible host **Activity** with `BIND_AUTO_CREATE | BIND_IMPORTANT | BIND_ADJUST_WITH_ACTIVITY`. This asks Android to bring the service process to foreground importance when its client is foreground and to account for the activity's visibility. The OS controls scheduling and can still kill either process. Release the binding when the host UI stops using the feature. An extension interaction activity supplies its own foreground lifecycle while it is on screen. The ordinary binding omits `BIND_INCLUDE_CAPABILITIES` and `BIND_ALLOW_ACTIVITY_STARTS`. The visible host initiates UI and the extension satisfies each operation's own foreground requirements. [Binding importance](https://developer.android.com/reference/android/content/Context#BIND_IMPORTANT) · [Activity visibility](https://developer.android.com/reference/android/content/Context#BIND_ADJUST_WITH_ACTIVITY) · [Foreground capabilities](https://developer.android.com/reference/android/content/Context#BIND_INCLUDE_CAPABILITIES)

## Camera preview inside the host

For `scanBarcode()`, prefer displaying the extension's own scanner activity in the host's task. On Android 13+ devices that support cross-app activity embedding, the host can show the scanner in a pane alongside its own controls. The extension opts in to trusted embedding and verifies the calling host for the scan. The visible extension activity owns the preview, camera permission prompt, Camera2 session and decoder. The host receives the barcode. Where the window or device cannot show the pane, the same activity can run full screen. [Cross-app activity embedding](https://developer.android.com/develop/ui/views/layout/activity-embedding#cross-application_embedding)

A preview at an arbitrary position in the host's view hierarchy needs a different contract. The fixture uses a separate, user-selected **share live camera preview** operation. The host provides a preview-sized `Surface` from a `SurfaceView` to the authenticated extension over Binder. The extension owns the camera device and capture session, selects a supported output size, and targets that surface. Binder carries the surface handle, session controls and results. Android's graphics pipeline carries the frames. The host draws its own controls and can consume or retain images delivered to a surface it supplies. Authorizing that stream is a separate choice from returning a decoded barcode. [Android Surface](https://developer.android.com/reference/android/view/Surface) · [Camera capture session outputs](https://developer.android.com/reference/android/hardware/camera2/CameraDevice#createCaptureSession(java.util.List,%20android.hardware.camera2.CameraCaptureSession.StateCallback,%20android.os.Handler))

For a host-owned surface, the extension's service must be eligible to open the camera while the host is visible. Process importance alone is insufficient. The host opts into Android's documented foreground capability binding only for this visible, authorized session, with the extension's own camera grant. A missing grant goes through the extension's permission activity first. On return, recheck visibility and eligibility before opening the camera. If Android disallows access, offer the visible extension scanner. [Foreground capability binding](https://developer.android.com/reference/android/content/Context#BIND_INCLUDE_CAPABILITIES)

The device probe waits for five host texture updates and checks the extension in Android’s active camera client list. It verifies closure after rotation, backgrounding, permission revocation and the death of each peer.

## When the extension needs a permission

1. Each executor uses its own application context for preflight. On API 23+ the extension uses `checkSelfPermission()` for its own declared permission and checks operation-specific eligibility. On API 14–22 it checks the install-time grant and reports unavailable if missing. On API 23+ a runtime-permission-capable extension can request a declared dangerous permission. A missing manifest declaration, special access, role or policy restriction gets its own outcome.
2. The visible host launches the verified extension approval activity for result. Before showing anything the activity validates the system-reported calling package, its dedicated UID and its signer. The extension displays who will receive the data and what it will share. On approval it requests its own declared dangerous permission if necessary. Refusal or cancellation returns a canceled result.
3. For live sharing the extension issues an opaque Binder token **after** approval. The activity and service share the extension process. The service accepts only its own local token instance, checks the authenticated host UID and operation, and consumes it once within 60 seconds. Process death invalidates outstanding tokens. The service rechecks its grant before opening the camera. For result-only scanning the extension instead opens its private scanner and forwards the decoded result.
4. A new session requires fresh consent even if Android remembers the permission grant. The host starts the live binding only after its activity resumes with the approval and a surface. Denial does not trigger a second prompt or a switch to another provider.

[Requesting permissions](https://developer.android.com/training/permissions/requesting) · [Activity result caller](https://developer.android.com/reference/android/app/Activity#getCallingPackage())

## Stateful APIs and failure

The process that opens a scanner, listener or other stateful resource owns it until completion. A feature that needs a session exposes a typed handle with a bounded lifetime, a callback and an idempotent close. It serializes operations on that handle, rejects calls from other UIDs and stale generations, and releases resources on cancellation, timeout or client death. Permission revocation or loss of required visibility stops the affected operation. App code receives results asynchronously, off the Binder dispatch thread.

| Event | Observable behavior |
| --- | --- |
| Host process dies | Extension observes client Binder death, closes resources, cancels pending UI work and discards results. Normal release uses explicit close. |
| Extension dies or its package is replaced | Host fails pending calls and invalidates handles. Reconnect for a new session after verifying the service and signer again. If Android reports a dead binding, unbind it before binding again. |
| A reply is lost after a side effect might have happened | Report outcome unknown. Reconnect does not replay work. An operation may offer reconciliation by request ID if its implementation durably records completion. |
| Host rotates or leaves the app | Reattach eligible user interaction through Activity Result state. Close visibility-dependent work when its owning UI stops. Ignore late callbacks for a closed request. |

[Binder death notifications](https://developer.android.com/reference/android/os/IBinder#linkToDeath(android.os.IBinder.DeathRecipient,%20int)) · [Service connection lifecycle](https://developer.android.com/reference/android/content/ServiceConnection#onBindingDied(android.content.ComponentName))

## Verification

Build and install the two APKs before running the device test on a disposable English-language emulator with a camera. The script clears data for the two fixture packages. Pick **Scan barcode in extension** to try the result-only flow with a QR code in the camera scene.

```sh
gradle -p compatibility :assemblePreviewHostDebug :assemblePreviewExtensionDebug
adb install -r compatibility/build/outputs/apk/previewHost/debug/*.apk
adb install -r compatibility/build/outputs/apk/previewExtension/debug/*.apk
ANDROID_SERIAL=emulator-5554 python3 compatibility/check_preview.py
```

On APIs 22, 23 and 24, the host displayed live frames and the extension owned the camera. API 22 used the install-time camera grant. APIs 23 and 24 exercised Android’s permission denial and grant UI.

On the API 37.0 x86_64 emulator, Android reported the extension UID as `BOUND_TOP` during the live preview and `CACHED_EMPTY` after the host left. Both the consent cancellation and OS denial paths left the camera closed. Rotation, revocation, backgrounding and stopping either app released the active camera. This emulator used an emulated back camera, the minigbm graphics allocator and virtio-gpu transport.

The result-only flow was also exercised on API 36 with a QR image as the emulator's camera input. To assert the result in the same smoke test, pass `--expect-barcode 'ExtensionEngine test scan'` with a camera scene containing that value. The Android permission dialog belongs to the extension in both flows.
