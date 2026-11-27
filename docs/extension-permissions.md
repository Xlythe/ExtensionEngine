# Extension-owned operations over Binder

**Proposed for approval.** A host can ask a trusted extension to perform a specific operation that Android permits the extension to perform under its own UID. For example, a host without camera permission could offer “Scan with Scanner Extension.” The extension shows the camera UI and returns the decoded barcode. The camera grant belongs to the extension throughout.

Today, `Extension.getExtension()` loads the extension class into the host process. Calling an Android API there uses the host's privileges, even when the class or its resources came from the extension APK. A participating extension would publish a bound service running under its own application UID. Existing Java calls and embedded views would keep their current behavior. [Current loader](../extension-engine/src/main/java/com/xlythe/engine/extension/Extension.java) · [Android bound services](https://developer.android.com/develop/background-work/services/bound-services)

## Who chooses where a call runs?

The host invokes an explicit, typed feature such as `scanBarcode()`. An embedded extension view can ask the host to start that feature through the same interface. The host owns the routing policy and the UI for selecting a provider. Each operation declares its supported Android versions, local and remote implementations, required permissions, and whether execution needs a visible activity. The extension independently chooses which operations it offers to each host. The provider choice identifies the extension and the data the host will receive. Device or profile policy that disallows the requested operation ends the request.

| At the user's action | Routing decision |
| --- | --- |
| A local implementation exists and its prerequisites are satisfied | Execute locally. A user who explicitly selected an extension can use that provider instead. |
| The host lacks a prerequisite | Offer the configured, verified extension if it advertises that operation. A host permission denial alone never authorizes switching providers. A prior explicit provider choice can be reused for the same feature. |
| The selected extension has its prerequisites | Execute in the extension. Open its activity for operations that require visible UI, including scanning. |
| The extension lacks a requestable runtime permission | Return an interaction request for that specific operation. Follow the permission flow below. |
| The operation is unsupported, policy blocks it, or a system call throws `SecurityException` | Return a typed failure. A permission check is a preflight. Android evaluates the actual call, including AppOps and while-in-use restrictions. Do not reroute or replay a failed call. |

Ordinary AOSP calls continue to execute where the calling class runs. Existing extension authors opt into the new method at the call site. The Binder contract carries versioned operation IDs and bounded, typed data. Class names, method names, framework managers and arbitrary intents are outside the wire format. The existing in-process reflection helper has no remote entry point. [Android AIDL](https://developer.android.com/develop/background-work/services/aidl)

## Trust and binding

Use the existing host-specific registration to locate the extension. The extension names its service and, if needed, its interaction activity in registration metadata. The host verifies package ownership of both components and applies the merged signing policy before binding by explicit component. Each side configures the exact peer packages eligible for each operation. Separately signed peers register reciprocal package-specific certificate pins in their own processes. Package visibility must let each side verify the other. An older extension without a compatible endpoint reports the feature as unavailable. [Merged signer policy](../extension-engine/src/main/java/com/xlythe/engine/extension/Security.java)

On every inbound Binder operation, including session methods, the extension checks the transaction's calling UID against the configured host package and its current signers before dispatch. Reject ambiguous shared UIDs for this protocol and bind requests and callbacks to the authenticated session. A caller-supplied package string, possession of a forwarded session Binder, or a matching certificate alone cannot authorize an operation. [Binder caller identity](https://developer.android.com/reference/android/os/Binder#getCallingUid())

Bind from the visible host **Activity** with `BIND_AUTO_CREATE | BIND_IMPORTANT | BIND_ADJUST_WITH_ACTIVITY`. This asks Android to bring the service process to foreground importance when its client is foreground and to account for the activity's visibility. The OS controls scheduling and can still kill either process. Release the binding when the host UI stops using the feature. An extension interaction activity supplies its own foreground lifecycle while it is on screen. The protocol does not request `BIND_INCLUDE_CAPABILITIES` or `BIND_ALLOW_ACTIVITY_STARTS`. The visible host initiates UI and the extension satisfies each operation's own foreground requirements. [Binding importance](https://developer.android.com/reference/android/content/Context#BIND_IMPORTANT) · [Activity visibility](https://developer.android.com/reference/android/content/Context#BIND_ADJUST_WITH_ACTIVITY) · [Foreground capabilities](https://developer.android.com/reference/android/content/Context#BIND_INCLUDE_CAPABILITIES)

## When the extension needs a permission

1. Each executor uses its own application context for preflight. On API 23+ the extension uses `checkSelfPermission()` for its own declared permission and checks operation-specific eligibility. On API 14–22 it checks the install-time grant and reports unavailable if missing. On API 23+ a runtime-permission-capable extension can request a declared dangerous permission. A missing manifest declaration, special access, role or policy restriction gets its own outcome.
2. For an eligible request, the service issues a short-lived, single-use interaction ID scoped to the host UID, operation and session. A visible host activity launches the verified extension activity for result in response to the user's action. A background caller receives `NeedsUserInteraction` and can present it when the user returns.
3. The extension activity validates the system-reported calling package, its UID and signing identity, and the pending request. It claims the request once, shows which feature and host need it, and uses Android's runtime permission UI. Permission names come from the extension's operation definition. Recreating the activity reattaches to its in-progress request. Reusing an expired or consumed ID fails.
4. On return, the extension rechecks the actual grant and execution conditions. For camera scanning it completes the scan while its own activity is visible and returns only the decoded value. Cancellation, denial and revocation end the request. The UI may offer a user-initiated Settings action when appropriate. It does not loop on a denial. A one-time grant is checked again on the next request.

The pending interaction lives independently of the visible host binding so switching to the extension activity can release that binding. Keep the host's client-liveness Binder for the interaction and expire the record if the host dies. If the extension process dies, the old ID expires and the user can start a fresh action. [Requesting permissions](https://developer.android.com/training/permissions/requesting) · [Activity result caller](https://developer.android.com/reference/android/app/Activity#getCallingPackage()) · [Background activity launches](https://developer.android.com/guide/components/activities/background-starts)

## Stateful APIs and failure

The process that opens a scanner, listener or other stateful resource owns it until completion. A feature that needs a session exposes a typed handle with a bounded lifetime, a callback and an idempotent close. It serializes operations on that handle, rejects calls from other UIDs and stale generations, and releases resources on cancellation, timeout or client death. Permission revocation or loss of required visibility stops the affected operation. App code receives results asynchronously, off the Binder dispatch thread.

| Event | Observable behavior |
| --- | --- |
| Host process dies | Extension observes client Binder death, closes resources, cancels pending UI work and discards results. Normal release uses explicit close. |
| Extension dies or its package is replaced | Host fails pending calls and invalidates handles. Reconnect for a new session after verifying the service and signer again. If Android reports a dead binding, unbind it before binding again. |
| A reply is lost after a side effect might have happened | Report outcome unknown. Reconnect does not replay work. An operation may offer reconciliation by request ID if its implementation durably records completion. |
| Host rotates or leaves the app | Reattach eligible user interaction through Activity Result state. Close visibility-dependent work when its owning UI stops. Ignore late callbacks for a closed request. |

[Binder death notifications](https://developer.android.com/reference/android/os/IBinder#linkToDeath(android.os.IBinder.DeathRecipient,%20int)) · [Service connection lifecycle](https://developer.android.com/reference/android/content/ServiceConnection#onBindingDied(android.content.ComponentName))

## First implementation to approve

Build one two-APK, user-initiated operation before expanding the API surface. The proposed fixture is a visible camera scan with a small typed result. Test the local route, the remote route with a pre-granted permission, and the extension's grant, deny, cancel and revoke paths. Verify that the permission dialog names the extension and the host's grant stays unchanged.

On API 37, measure the extension service's process importance with the host foreground, background and unbound, separately from camera access while the extension activity is visible. Exercise rotation during the prompt, death of either process, an update between bind and reconnect, two simultaneous requests, and denial of a wrong-package caller even when it shares a trusted signing key. Use API 22 for install-time behavior and APIs 23–24 for the runtime permission transition. These are acceptance tests for the implementation.
