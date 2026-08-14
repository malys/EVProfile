package com.evsuite.profile.api;

/**
 * EVProfile's external control API — driving profiles.
 *
 * Any app signed with the same platform key can bind this and list or apply EVProfile's
 * driving profiles. It is deliberately narrow: profiles only, no vehicle read and no raw
 * property write. It is not tied to any particular caller.
 *
 * Guarded by the signature permission com.evsuite.profile.permission.CONTROL_PROFILES.
 */
interface IProfileControl {

    /** EVProfile profiles. Bundle: "ids" String[], "names" String[], "defaultId" String. */
    Bundle listProfiles();

    /**
     * Applies a whole profile. Bundle result: "ok" boolean, "verdict" String
     * (ALLOWED | REFUSED_MOVING | REFUSED_UNKNOWN_SPEED | UNSUPPORTED | ERROR),
     * "detail" String optional.
     */
    Bundle applyProfile(String profileId);

    /**
     * Shows EVProfile's profile picker overlay and leaves the choice to the driver.
     * Same result keys as applyProfile. Refused while the car moves or when its speed is
     * unreadable — the picker exists to apply a profile — and UNSUPPORTED when EVProfile
     * holds no profile to offer.
     */
    Bundle showProfilePicker();
}
