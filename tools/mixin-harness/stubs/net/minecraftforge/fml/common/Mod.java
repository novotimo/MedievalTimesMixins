package net.minecraftforge.fml.common;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
public @interface Mod { String modid(); String name() default ""; String version() default ""; String dependencies() default ""; String acceptableRemoteVersions() default ""; }
