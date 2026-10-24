package tribixbite.cleverkeys

/**
 * How incognito mode combines with the preferences it suspends.
 *
 * Incognito is a suspension, not a rewrite: the user's own choices for learning, autocorrect,
 * auto-capitalisation and clipboard history stay exactly as they set them, and incognito folds
 * into each of them at READ time through [allows]. Two consequences fall out of that shape and
 * both are deliberate.
 *
 * First, nothing is stomped. Storing "effective autocorrect" in a field and recomputing it on
 * toggle is precisely the bug shape this codebase has already paid for twice -- #154, where
 * mapping a toggle onto `vibrate_custom` forced every user down the slow vibrator path, and #161,
 * where the portrait height slider stomped the landscape one. A derived value that lives in a
 * field has writers, and writers disagree. A derived value computed at the read site has none.
 *
 * Second, incognito can only ever suspend, never enable: [allows] with `incognito = true` is false
 * for both states of the user's preference, so turning incognito on can never switch a feature
 * the user had off. Turning it off restores whatever the user had chosen, with no migration and
 * no remembered incognito-time state.
 *
 * The fold happens in [Config]'s `*Effective` properties, so every behaviour site reads one named
 * thing and a future reader cannot forget the incognito half by reading the raw field.
 */
object IncognitoPolicy {
    /**
     * @param userPreference the user's own setting for the feature, untouched by incognito
     * @param incognito whether incognito mode is currently on
     * @return whether the feature may act right now
     */
    @JvmStatic
    fun allows(userPreference: Boolean, incognito: Boolean): Boolean = userPreference && !incognito
}
