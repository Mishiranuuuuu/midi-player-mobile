# ProGuard rules for AutoClicker

# Keep Accessibility Service
-keep class com.autoclicker.app.service.AutoClickerAccessibilityService { *; }

# Keep Compose
-dontwarn androidx.compose.**
