plugins {
    id("starter.android.library")
    id("com.squareup.wire")
}

wire { kotlin { javaInterop = false } }
