# NativeBridge is looked up from Rust by string name (find_class) and
# its `external fun` + `@JvmStatic` callback methods are resolved by
# JNI symbol mangling / call_static_method by name. Keep the class name
# and all members verbatim.
-keep class me.phie.tawc.compositor.NativeBridge { *; }
