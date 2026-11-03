# 模块入口（META-INF/xposed/java_init.list 中列出）必须保留
-keep class com.nighticon.xposed.ModuleEntry { *; }
-adaptresourcefilenames META-INF/xposed/java_init.list
