package io.github.dgproman.pihome.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

// Each key is a place in one of two back stacks: the one for being signed out
// and the one for being signed in. Which of the two is shown is not a place to
// navigate to; see PihomeApp.

/** Signed out: how to get connected. */
@Serializable
data object Welcome : NavKey

/** Signed in: the relays, sensors and devices of the house. */
@Serializable
data object House : NavKey

/** Signed in: who this is, which hub, and the way out. */
@Serializable
data object Account : NavKey
