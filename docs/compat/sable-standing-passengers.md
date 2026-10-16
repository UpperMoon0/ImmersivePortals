# Standing passengers at dimension stacks

A player standing on a deck or balloon envelope has no vanilla vehicle. Previously the client selected ordinary player prediction, the server accepted that independent teleport, and ship migration waited for its center of mass. Mounted-seat tests could pass while a standing player crossed without the ship.

The standing path uses Sable's live collision tracking, excludes removed or foreign-world support, and keeps ordinary nearby players outside the handoff. Cross-dimension, unscaled standing crossings request the existing correlated server-first protocol. The server independently resolves the supporting body and checks native point motion before migrating its dependency chain. Standing players are captured from ordinary world chunks and transferred with transformed world positions and velocities; both commit and rollback restore support to the live body in the matching world.

Client prediction is held only during the bounded correlated handoff. Standing facing is transformed in world space after the destination full sync exists, while seat riders retain their existing local-facing handling. Respawn, unrelated dimension changes, mounting, rejection, cleanup and expiry release the hold.

Run python3 tools/verify.py standing-balloon in a disposable checkout with Java 21 and a graphical display or Xvfb. The pinned official Aeronautics 1.3.1 bundle is checksum-verified by the existing downloader. The fixture has a 5x5 plank deck, adjustable burner, powered lever, fence supports and a plain radius-7 white-envelope shell. The report does not specify envelope dimensions; the test requires a natural lift/weight reserve above 1.1 before release. Separate cases place the original real player on the envelope and deck without a seat.

Setup stages the assembled ship near the real Nether logical ceiling, heats the burner during an explicit pre-flight hold, and waits for native standing collision on both server and client. Release permits only the burner's natural forces. The test does not write flight-time body poses, velocities, forces, gravity or atmosphere and adds no command force-load ticket. Both exclusive destination body ownership and player support must agree, followed by 80 server ticks and 40 client ticks of standing continuity. Exact blocks, active destination burner and displacement receipts are retained.

UDP and TCP lanes are mandatory in CI and fresh release verification. Reused release evidence must include the TCP standing job and the UDP powered CONTROL follow-up at the exact tagged commit; the old 47-job contract cannot satisfy the new 48-job contract.

Validation is in progress. Source inspection and successful compilation alone do not certify this fixture or resolve any separate Aeronautics gas-state migration defect.

The UDP standing cases follow the existing powered CONTROL acceptance in the same server/client process. The mounted manifests and client/server pass receipts are retained separately before the unmounted cases start. TCP remains isolated. This removes one redundant dedicated-server/graphical-client startup pair, without dropping transports, cases, mounted assertions, or existing ship-size isolation.

## Hull entry at stack seams

Sable's default atmosphere explicitly smooths pressure to zero over the last 40 meters before a dimension's logical ceiling. Waiting for COM alone can therefore strand an otherwise functioning balloon at the seam. Global vertical stack connectors now detect the swept leading hull; ordinary finite portals retain COM traversal. The previous substep stores both vertical hull extrema. If a tip enters and gravity immediately reverses it before COM enters, that same entered tip leaving the destination triggers the return transaction. The existing native movement fixture checks leading entry with COM three blocks behind the plane and an immediate return with COM still outside, preserving all blocks.

The burner regression uses the standard Nether ceiling / Overworld floor pair under unchanged native atmosphere. Separate source-atmosphere lift reserve, exclusive ownership, unmounted support, exact payload and continued destination motion remain required. The test does not claim that every single-burner envelope can reach the Overworld ceiling: that dimension's lower high-altitude pressure is an independent physical limit.
