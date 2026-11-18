package com.bruhascended.dustit.ml

import androidx.annotation.StringRes
import com.bruhascended.dustit.R

/**
 * Classes predicted by the junk classifier.
 *
 * Declaration order must match the model's output order (its embedded `label_names.txt`);
 * `JunkCategoryTest` fails if they drift. Enum names are persisted in Room, so rename with care.
 */
enum class JunkCategory(
    val modelLabel: String,
    @StringRes val title: Int,
    @StringRes val description: Int,
    /** Whether DustIt offers this category as a cleanup suggestion. */
    val isClutter: Boolean,
    /**
     * Mostly text on a fixed layout. Different chats or pages look identical at thumbnail scale,
     * so these are never matched as "similar" by appearance, only as exact duplicates.
     */
    val isTextHeavy: Boolean,
) {
    Documents("assignments", R.string.category_documents, R.string.category_documents_desc, isClutter = true, isTextHeavy = true),
    Blurry("blurred", R.string.category_blurry, R.string.category_blurry_desc, isClutter = true, isTextHeavy = false),
    Chats("chats", R.string.category_chats, R.string.category_chats_desc, isClutter = true, isTextHeavy = true),
    Food("food", R.string.category_food, R.string.category_food_desc, isClutter = false, isTextHeavy = false),
    Greetings("goodwishes", R.string.category_greetings, R.string.category_greetings_desc, isClutter = true, isTextHeavy = true),
    Memes("memes", R.string.category_memes, R.string.category_memes_desc, isClutter = true, isTextHeavy = true),
    Notices("notices", R.string.category_notices, R.string.category_notices_desc, isClutter = true, isTextHeavy = true),
    Other("other", R.string.category_other, R.string.category_other_desc, isClutter = false, isTextHeavy = false),
    Pets("pets", R.string.category_pets, R.string.category_pets_desc, isClutter = false, isTextHeavy = false);

    companion object {
        val clutter: List<JunkCategory> = entries.filter { it.isClutter }
    }
}

/** Score cut-offs applied at query time, so tuning them never requires a rescan. */
object Confidence {
    /** At or above this, a photo is pre-selected for deletion. Same default the original app used. */
    const val LIKELY = 0.9f

    /** Between this and [LIKELY], a photo is shown for review but left unselected. */
    const val MAYBE = 0.6f
}
