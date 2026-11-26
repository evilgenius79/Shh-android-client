# Rules for the release build (R8).

# BouncyCastle registers its algorithms by class name and the JCA instantiates them reflectively,
# so everything under the provider packages has to survive. The low level implementations are
# referenced from those classes directly and are kept or dropped by R8 as usual.
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn org.bouncycastle.**

# sshj: small, and it looks classes up by name in a few places (security provider, factories).
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }

# Optional parts of sshj and BouncyCastle that refer to APIs Android does not have. They are
# never reached: GSSAPI/Kerberos login, the ssh-agent Unix socket client, LDAP certificate stores.
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.login.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
-dontwarn org.slf4j.impl.**

# Keep line numbers so that stack traces from users are readable, but hide file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
