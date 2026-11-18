# Abandoned American Town environment update

This update changes the playable arena from a sealed corridor into a compact outdoor post-apocalyptic town street.

## Changes
- Asphalt roadway with faded double-yellow centre markings and worn lane edges.
- Concrete sidewalks and neglected grass verges.
- Procedurally placed ruined low-rise houses/storefronts on both sides of the street.
- Abandoned rust-coloured cars with dark windows and tyres.
- Dead roadside trees and scattered road debris for cover.
- Expanded playable street width and outdoor zombie spawn placement.
- Increased stage kill target from the previous progression formula to 8 + stage/3 (capped at 28) to make encounters feel more populated.
- Existing zombie models, animation assets, weapons, HUD, and combat systems are retained.

## Notes
The scene uses the project's existing OpenGL primitives and textures to avoid introducing external asset licensing or download dependencies. The buildings, cars, and trees are stylized environmental blockouts rather than newly authored photogrammetry-grade assets. A full AAA art pass would still require dedicated high-detail building/vehicle/tree meshes, PBR material maps, and authored environment props.

## Build verification
The source archive did not include a Gradle wrapper and this editing environment does not have Gradle/Android SDK available, so an Android APK build could not be run here. Please open the project in Android Studio and run a Gradle sync/build to verify on the target device.
