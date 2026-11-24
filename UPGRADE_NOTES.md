# ZOMBIE SHOOTER – upgrade notes

* New multi-part 3D models (zombie male/female/brute, infected dog, soldier, 5 guns) loaded from `assets/models`.
* Skeletal animation from model parts: walk, run, limp, stomp, attack, dog gallop, corpses falling.
* Soldier holds the real gun model with two-hand IK, recoil, reload tilt.
* Full 360° world: sky dome with clouds, infinite ground, buildings down the street, barricades, wrecks.
* Virtual joystick (move/strafe) + drag-to-look + FIRE button.
* Muzzle flash, sparks, smoke, brass casings, tracers; layered synthesized gun sounds.
* Start-up freeze fixed: models/sounds load on background threads, GPU vertex buffers.
