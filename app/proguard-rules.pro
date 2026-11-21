# R8 rules of the application. Library-specific rules are added with the libraries (architecture §9).

# Play Asset Delivery (ADR-0012): asset-delivery-ktx annotates a callback with an annotation of
# play-services-basement, which the application does not ship. It is a marker for Google's own tooling,
# never read at runtime; it became reachable once Settings → Langues asks for Play's confirmation (M6-06).
-dontwarn com.google.android.gms.common.annotation.NoNullnessRewrite
