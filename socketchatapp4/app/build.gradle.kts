plugins {
    id("com.android.application")

}

android {
    buildFeatures {
        viewBinding = true
        dataBinding = true

        namespace = "com.example.socketchatapp"
        compileSdk = 35

        defaultConfig {
            applicationId = "com.example.socketchatapp"
            minSdk = 26
            targetSdk = 35
            versionCode = 1
            versionName = "1.0"

            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        buildTypes {
            release {
                isMinifyEnabled = false
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }

        buildFeatures {
            viewBinding = true
        }
    }

    dependencies {
        // مكتبات Android الأساسية
        implementation("androidx.appcompat:appcompat:1.6.1")
        implementation("com.google.android.material:material:1.11.0")
        implementation("androidx.constraintlayout:constraintlayout:2.1.4")
        implementation("androidx.activity:activity-ktx:1.8.2")

        implementation("com.github.bumptech.glide:glide:4.13.0")
        annotationProcessor("com.github.bumptech.glide:compiler:4.13.0")
        // لمكونات واجهة المستخدم
        implementation("androidx.cardview:cardview:1.0.0")
        implementation("androidx.recyclerview:recyclerview:1.3.2")

        // لاتصالات الشبكة
        implementation("com.squareup.okhttp3:okhttp:4.12.0")

        // لمعالجة الصوت
        implementation("androidx.media:media:1.7.0")

        // الاختبار
        testImplementation("junit:junit:4.13.2")
        androidTestImplementation("androidx.test.ext:junit:1.1.5")
        androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    }
}
dependencies {
    implementation(libs.play.services.location)
}
