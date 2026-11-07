# AndroidX implementation baseline

Checked on 2026-11-05. Project: PurposeGate.

## Declared versions

- Android Gradle Plugin: 9.4.1; Gradle: 9.8.1.
- Kotlin Compose compiler plugin: 2.4.21; JVM bytecode: 21.
- Compose UI / Foundation: 1.12.0.
- Material 3: 1.5.0-beta01 (existing Expressive API opt-ins retained).
- Miuix Nav Android: 0.9.4; replaces Navigation Compose as explicitly requested on 2026-11-06.
- Activity Compose: 1.13.0 (navigation-event dispatcher bridge required by Miuix).
- Lifecycle runtime-compose / viewmodel-compose / viewmodel-ktx: 2.11.0.
- Coroutines Android: 1.11.0.
- Android SDK: compile/target 37, minimum 31.

## Verified official implementations and samples

- [NavHost implementation](https://github.com/androidx/androidx/blob/androidx-main/navigation/navigation-compose/src/commonMain/kotlin/androidx/navigation/compose/NavHost.kt): route-based NavHost/composable, official back stack and predictive-back integration; public transition callbacks.
- [Navigation samples](https://github.com/androidx/androidx/blob/androidx-main/navigation/navigation-compose/samples/src/main/java/androidx/navigation/compose/samples/NavigationSamples.kt): rememberNavController and destination declarations. Only navigation wiring is referenced; UI remains Material 3.
- [StateFlow lifecycle collection](https://github.com/androidx/androidx/blob/androidx-main/lifecycle/lifecycle-runtime-compose/src/commonMain/kotlin/androidx/lifecycle/compose/FlowExt.kt): collectAsStateWithLifecycle for page state.
- [Material theme](https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/MaterialTheme.kt) and local material3-android 1.5.0-beta01 source JAR: MaterialExpressiveTheme and MotionScheme.expressive are available in the actual dependency.
- [Foundation lazy samples](https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/samples/src/main/java/androidx/compose/foundation/samples/LazyDslSamples.kt): keyed lazy content; existing LazyColumn/FlowRow layouts retained.

Upstream androidx-main is a reference, not the binary API version. Compilation against the declared dependencies is the compatibility check. No internal AndroidX implementation is copied into the application.

## Architecture and navigation

Activity-scoped PurposeGateViewModel owns immutable PurposeGateUiState in StateFlow. SavedStateHandle restores the selected peer tab. The repository is the UI's data boundary; PurposeGateStore remains the only preference writer and the existing accessibility-service contract stays in place. SharedPreferences changes from the service update observed UI state. Preference observation stops without UI subscribers; app-list loading uses Dispatchers.IO and viewModelScope. Resuming the Activity refreshes permissions and app data; foreground-only periodic refresh handles the local date rollover.

Miuix NavDisplay now owns the child navigation renderer, interactive swipe, predictive Back and transition lifecycle. PurposeNavigator persists a single NavBackStack with stable entry IDs. The page hierarchy helper calculates targets; it does not store a second live stack. Dashboard and Focus share one root entry; root Back exits Android. Root tab button transitions use Material motion effects crossfading, without hierarchical parallax, and keep the dock fixed.

The previous Navigation Compose visual emulation has been removed. See [MIUIX_NAV_COMPARISON.md](MIUIX_NAV_COMPARISON.md) for source references, behavioral differences and migration scope. MD3E components and the Material bottom sheet retain their own control gestures. No internal Miuix implementation is copied, and no whole-page input lock is enabled.

Text input, search/filter, unsaved rule drafts, Snackbar state and modal expansion are local presentation state. Saved preferences and application/business data flow through the ViewModel. App-list failures expose a retry action.

## Validation

Run assembleDebug and the targeted MiuixNavigationTest, PurposeGateViewModelTest, AppNavigationStateTest and FadingContentTest. Device installation and observed smoke-check results are reported separately.