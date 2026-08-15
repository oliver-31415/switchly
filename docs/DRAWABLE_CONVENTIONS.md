# Drawable conventions
Switchly keeps ordinary UI icons structurally identical so new icons are easy to review, replace, and maintain.

## Standard 24dp icon
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="960"
    android:viewportHeight="960">
    <path
        android:pathData="..."
        android:fillColor="?attr/colorOnSurface" />
</vector>
```

For standard UI icons, only `android:pathData` should normally differ. Multi-part artwork may use multiple `<path>` elements, but each path follows the same attribute order.

## Attribute order
Vector attributes:
1. `android:width`
2. `android:height`
3. `android:viewportWidth`
4. `android:viewportHeight`
5. optional vector-specific attributes such as `android:autoMirrored`

Path attributes:
1. `android:pathData`
2. `android:fillColor`
3. optional `android:strokeColor`
4. optional `android:strokeWidth`
5. optional stroke line-cap and line-join attributes

Drawable XML files omit the XML declaration to match the existing Android resource style.

## Validation
Run from the repository root:
```bash
python scripts/validate_vector_drawables.py
```

The script checks XML validity, the standard viewport, XML declarations, attribute order, and known size exceptions.
