package com.ironkinclan.model;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The bosses/activities the Ironkin Hall of Flame currently accepts personal best
 * submissions for (https://ironkinclan.com/hall-of-flame), and the NPC display name(s)
 * RuneLite reports for each one.
 * <p>
 * Matched against {@link net.runelite.api.Actor#getName()} rather than NpcID gameval
 * constants: several of these (Zulrah, Doom of Mokhaiotl) have no discoverable NpcID
 * constant even in the latest runelite-api release, while the boss's displayed name is
 * always available and naturally resilient to new NPC ID variants being added upstream.
 */
public enum BossActivity
{
	SHELLBANE_GRYPHON("Shellbane Gryphon", "Shellbane Gryphon"),
	MAD_ANGEL("Mad Angel", "Mad Angel"),
	MAGGOT_KING("Maggot King", "Maggot King"),
	// NPC name confirmed via the OSRS Wiki infobox (oldschool.runescape.wiki/w/Doom_of_Mokhaiotl):
	// ids 14707/14708/14709 for the base/shielded/burrowed forms, none yet in gameval's NpcID
	// (very new 2026 content) - all three phase names are included since the last-interacting
	// form before the kill/PB message isn't guaranteed to be the base one.
	DOOM_OF_MOKHAIOTL("Doom of Mokhaiotl (Floors 1-8)", "Doom of Mokhaiotl", "Doom of Mokhaiotl (Shielded)", "Doom of Mokhaiotl (Burrowed)"),
	ARAXXOR("Araxxor", "Araxxor"),
	AMOXLIATL("Amoxliatl", "Amoxliatl"),
	// The site's own hall-of-flame JSON API (fetched earlier) had this misspelled as
	// "Colloseum" (double L), but the live plugin-submit form's "boss" dropdown - the actual
	// submission endpoint's validation - spells it correctly. The final boss NPC is Sol Heredit.
	COLOSSEUM("Colosseum", "Sol Heredit"),
	VARDORVIS("Vardorvis", "Vardorvis"),
	WHISPERER("Whisperer", "The Whisperer", "Whisperer"),
	LEVIATHAN("Leviathan", "The Leviathan", "Leviathan"),
	DUKE_SUCELLUS("Duke Sucellus", "Duke Sucellus"),
	PHANTOM_MUSPAH("Phantom Muspah", "Phantom Muspah"),
	PHOSANIS_NIGHTMARE("Phosani's Nightmare", "Phosani's Nightmare"),
	// Gauntlet and Corrupted Gauntlet have distinctly-named final bosses, so no NPC ID
	// disambiguation is needed between the two.
	CORRUPTED_GAUNTLET("Corrupted Gauntlet", "Corrupted Hunllef"),
	GAUNTLET("Gauntlet", "Crystalline Hunllef"),
	ALCHEMICAL_HYDRA("Alchemical Hydra", "Alchemical Hydra"),
	HESPORI("Hespori", "Hespori"),
	VORKATH("Vorkath", "Vorkath"),
	GROTESQUE_GUARDIANS("Grotesque Guardians", "Dawn", "Dusk"),
	INFERNO("Inferno", "TzKal-Zuk"),
	ZULRAH("Zulrah", "Zulrah"),
	FIGHT_CAVES("Fight Caves", "TzTok-Jad");

	// The exact string to send in the "boss" field of a Hall of Flame submission - must
	// match the site's category name verbatim.
	public final String hallOfFlameName;
	private final Set<String> npcNames;

	// Built once all constants exist, rather than scanning every activity's npcNames on every
	// lookup - this runs on every InteractingChanged involving the local player, so it's worth
	// being an O(1) map lookup instead of an O(n*m) scan, even though neither is noticeable at
	// this scale.
	private static final Map<String, BossActivity> BY_NPC_NAME = new HashMap<>();

	static
	{
		for (BossActivity activity : values())
		{
			for (String npcName : activity.npcNames)
			{
				BY_NPC_NAME.put(npcName.toLowerCase(), activity);
			}
		}
	}

	BossActivity(String hallOfFlameName, String... npcNames)
	{
		this.hallOfFlameName = hallOfFlameName;
		this.npcNames = new HashSet<>(Arrays.asList(npcNames));
	}

	/**
	 * @return the activity whose NPC name(s) match {@code npcName} (case-insensitive), or
	 * {@code null} if the name isn't recognized.
	 */
	public static BossActivity forNpcName(String npcName)
	{
		return npcName == null ? null : BY_NPC_NAME.get(npcName.toLowerCase());
	}

	/**
	 * An additional phrase (case-insensitive) that must also appear in the chat message for a
	 * "(new personal best)" detection to actually count for this activity - {@code null} if the
	 * generic marker alone is sufficient (true for every activity except the one below).
	 * <p>
	 * Needed because Doom of Mokhaiotl reports more than one kind of personal best in the same
	 * chat stream: a PB per individual delve level (e.g. "Delve level 5 duration: 0:50 (new
	 * personal best)") *and* a separate PB for the full floors 1-8 run ("Delve level 1 - 8
	 * duration: 9:04 (new personal best)") - confirmed in-game. Only the latter matches this
	 * Hall of Flame category; without this check, a per-level PB would get wrongly submitted as
	 * the floors-1-8 record.
	 */
	public String requiredMessagePhrase()
	{
		return this == DOOM_OF_MOKHAIOTL ? "level 1 - 8 duration" : null;
	}
}
