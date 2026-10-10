import com.google.gms.googleservices.GoogleServicesPlugin.GoogleServicesPluginConfig
import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

extensions.configure<GoogleServicesPluginConfig> {
    missingGoogleServicesStrategy = MissingGoogleServicesStrategy.IGNORE
}
