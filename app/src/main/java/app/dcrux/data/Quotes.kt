package app.dcrux.data

data class Quote(val text: String, val source: String)

/**
 * Short lines from public-domain works and traditional sayings, each with its source.
 * Five are shown each day; the set rotates daily without any network call.
 */
object Quotes {
    val all = listOf(
        Quote("Arise, awake, and stop not till the goal is reached.", "Swami Vivekananda, after the Katha Upanishad"),
        Quote("You have the right to work only, but never to its fruits.", "Bhagavad Gita 2.47"),
        Quote("Yoga is skill in action.", "Bhagavad Gita 2.50"),
        Quote("Let a man lift himself by his own self alone; let him not lower himself.", "Bhagavad Gita 6.5"),
        Quote("Truth alone triumphs.", "Mundaka Upanishad 3.1.6"),
        Quote("A journey of a thousand miles begins with a single step.", "Lao Tzu, Tao Te Ching"),
        Quote("Where the mind is without fear and the head is held high.", "Rabindranath Tagore, Gitanjali"),
        Quote("Strength does not come from physical capacity. It comes from an indomitable will.", "M. K. Gandhi, Young India (1920)"),
        Quote("Take up one idea. Make that one idea your life.", "Swami Vivekananda, Raja Yoga"),
        Quote("All power is within you; you can do anything and everything.", "Swami Vivekananda"),
        Quote("What you would do tomorrow, do today; what you would do today, do now.", "Kabir"),
        Quote("Well done is better than well said.", "Benjamin Franklin, Poor Richard's Almanack"),
        Quote("He who has a why to live for can bear almost any how.", "Friedrich Nietzsche, Twilight of the Idols"),
        Quote("Knowing is not enough; we must apply. Willing is not enough; we must do.", "Johann Wolfgang von Goethe"),
        Quote("Our greatest glory consists not in never falling, but in rising every time we fall.", "Oliver Goldsmith, The Citizen of the World"),
        Quote("Nothing great was ever achieved without enthusiasm.", "Ralph Waldo Emerson, Circles"),
        Quote("Trust thyself: every heart vibrates to that iron string.", "Ralph Waldo Emerson, Self-Reliance"),
        Quote("Dripping water hollows out a stone.", "Ovid, Epistulae ex Ponto"),
        Quote("Fortune favours the bold.", "Virgil, Aeneid"),
        Quote("Well begun is half done.", "Proverb, quoted by Aristotle in Politics"),
        Quote("First say to yourself what you would be; and then do what you have to do.", "Epictetus, Discourses"),
        Quote("No great thing is created suddenly.", "Epictetus, Discourses"),
        Quote("Great works are performed not by strength but by perseverance.", "Samuel Johnson, Rasselas"),
        Quote("To strive, to seek, to find, and not to yield.", "Alfred, Lord Tennyson, Ulysses"),
        Quote("I am the master of my fate, I am the captain of my soul.", "W. E. Henley, Invictus"),
        Quote("Fall seven times, stand up eight.", "Japanese proverb"),
        Quote("No longer talk at all about the kind of man a good man ought to be, but be such.", "Marcus Aurelius, Meditations"),
        Quote("It is not that we have a short time to live, but that we waste a lot of it.", "Seneca, On the Shortness of Life"),
        Quote("If one advances confidently in the direction of his dreams, he will meet with a success unexpected in common hours.", "Henry David Thoreau, Walden"),
        Quote("All that we are is the result of what we have thought.", "Dhammapada, verse 1"),
    )

    fun today(count: Int = 5): List<Quote> {
        val day = Dates.dayOfEpoch()
        val start = ((day * count) % all.size).toInt()
        return List(count) { all[(start + it) % all.size] }
    }
}
