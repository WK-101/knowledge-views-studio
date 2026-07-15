/**
 * The evaluation corpus and its query relevance judgements ("qrels").
 *
 * This is the load-bearing part of the whole exercise, and its integrity rests on one discipline: the
 * relevant answers for each query were decided by *reading the documents and judging meaning*, before the
 * search was ever run against them. A corpus reverse-engineered from what the ranker already does would
 * measure nothing but its own assumptions — it would always score well, and tell us nothing. So the
 * judgements here are deliberately the "right answer a knowledgeable person would give", and where the
 * current ranker disagrees, that is a finding, not an error in the qrels to be corrected.
 *
 * The domain is a personal knowledge base about coffee — chosen because it has natural vocabulary overlap
 * (brewing / extraction, grind / burr, beans / roast) that separates a keyword-only match from a
 * meaning-aware one, and because a non-specialist can still verify the judgements by reading.
 *
 * Each document has a title, optional headings and tags (the boosted fields), and a body. IDs are stable
 * so qrels can reference them.
 */

export interface EvalDoc {
  readonly id: string;
  readonly title: string;
  readonly headings?: readonly string[];
  readonly tags?: readonly string[];
  readonly body: string;
}

export interface EvalQuery {
  readonly query: string;
  /** Doc ids a knowledgeable person judges relevant to this query — decided by meaning, not by search. */
  readonly relevant: readonly string[];
  /** Why these, and not others — so a reader can check the judgement rather than take it on faith. */
  readonly rationale: string;
}

export const EVAL_CORPUS: readonly EvalDoc[] = [
  {
    id: "grind-size",
    title: "Grind Size and Extraction",
    headings: ["Why grind matters", "Dialing in"],
    tags: ["grinding", "extraction"],
    body: `The single biggest lever over how your coffee tastes is how finely the beans are ground.
      A finer grind exposes more surface area, so water pulls flavour out faster — too fine and you
      over-extract into bitterness; too coarse and the shot runs sour and thin because the water never
      pulled enough out. Dialing in means adjusting grind until extraction lands in the sweet spot.`,
  },
  {
    id: "burr-vs-blade",
    title: "Burr Grinders versus Blade Grinders",
    headings: ["How burrs work", "Why blades lose"],
    tags: ["grinding", "equipment"],
    body: `A burr grinder crushes beans between two rotating abrasive surfaces set a fixed distance apart,
      producing particles of a consistent size. A blade grinder simply chops with a spinning blade,
      giving a chaotic mix of dust and boulders. Consistency is everything for even extraction, which is
      why any serious setup starts with a burr grinder.`,
  },
  {
    id: "pour-over",
    title: "Pour-Over Brewing",
    headings: ["The bloom", "Pour technique"],
    tags: ["brewing", "manual"],
    body: `Pour-over is a manual brewing method where you pour hot water over a bed of grounds in a filter
      cone. Start with the bloom — a small pour that wets the grounds and lets trapped gas escape — then
      pour in slow concentric circles to keep the bed even. The result is a clean, bright cup that
      showcases the character of the beans.`,
  },
  {
    id: "french-press",
    title: "French Press Immersion Brewing",
    headings: ["Steep time", "The plunge"],
    tags: ["brewing", "immersion"],
    body: `The French press is an immersion method: grounds steep in hot water for around four minutes,
      then a mesh plunger separates them from the liquid. Because the grounds sit in contact with the
      water the whole time and the metal filter lets oils through, the body is heavy and the cup is rich
      rather than clean. Use a coarse grind so the plunger does not clog.`,
  },
  {
    id: "espresso-basics",
    title: "How Espresso Works",
    headings: ["Pressure and pucks", "Reading the shot"],
    tags: ["espresso", "brewing"],
    body: `Espresso forces hot water through a compacted puck of very finely ground coffee at nine bars of
      pressure, producing a small, intense shot topped with crema. The fine grind and high pressure mean
      extraction happens in seconds, so tiny changes in grind or dose move the result a lot. A shot that
      runs too fast tastes sour and underdeveloped; too slow and it turns bitter.`,
  },
  {
    id: "roast-levels",
    title: "Roast Levels and Flavour",
    headings: ["Light to dark", "What roasting changes"],
    tags: ["roasting", "beans"],
    body: `Roasting transforms green coffee beans through heat. A light roast keeps more of the bean's
      origin character — fruit, acidity, floral notes — while a dark roast develops heavier, roastier,
      more bitter flavours and hides origin. Roast level is not about strength; it is about which flavours
      the heat brings forward and which it burns away.`,
  },
  {
    id: "bean-origin",
    title: "Coffee Bean Origins",
    headings: ["Terroir", "Single origin vs blend"],
    tags: ["beans", "sourcing"],
    body: `Where a coffee is grown shapes its flavour as much as how it is roasted or brewed. Altitude,
      soil, and climate — the terroir — give beans from Ethiopia their bright berry notes and beans from
      Sumatra their earthy heaviness. A single-origin coffee comes from one place and tastes of it; a
      blend mixes origins for balance and consistency.`,
  },
  {
    id: "water-quality",
    title: "Water Chemistry for Brewing",
    headings: ["Minerals matter", "Too hard, too soft"],
    tags: ["water", "brewing"],
    body: `Coffee is over ninety-eight percent water, so the water you brew with is not a detail. Some
      dissolved minerals are needed to pull flavour out of the grounds — distilled water brews flat,
      lifeless coffee — but too much hardness mutes brightness and scales your machine. The target is
      moderately mineralised water, neither distilled nor tap-hard.`,
  },
  {
    id: "milk-steaming",
    title: "Steaming Milk for Lattes",
    headings: ["Stretching and texturing", "Microfoam"],
    tags: ["milk", "espresso"],
    body: `Steaming milk has two phases: stretching, where the steam wand introduces air to grow the
      volume, and texturing, where you submerge the wand to spin the milk into a smooth glossy microfoam
      with no large bubbles. Good microfoam pours like wet paint and is what makes latte art possible on
      an espresso drink.`,
  },
  {
    id: "storage",
    title: "Storing Coffee Beans",
    headings: ["The enemies of freshness", "Freeze or not"],
    tags: ["beans", "storage"],
    body: `Roasted beans go stale from exposure to oxygen, light, heat, and moisture. Keep them in an
      opaque airtight container at room temperature and grind only what you need just before brewing —
      ground coffee stales far faster than whole beans. Freezing works for long-term storage if the beans
      are sealed against moisture, but the fridge is the worst place: humid and full of odours.`,
  },
  {
    id: "cold-brew",
    title: "Cold Brew Concentrate",
    headings: ["Long steep, cold water", "Dilution"],
    tags: ["brewing", "cold"],
    body: `Cold brew steeps coarsely ground coffee in cold or room-temperature water for twelve to
      twenty-four hours, then filters out the grounds. The long, cold extraction pulls out sweetness and
      body while leaving behind much of the acidity and bitterness that hot water extracts, giving a
      smooth concentrate you dilute to taste over ice.`,
  },
  {
    id: "cupping",
    title: "Cupping and Tasting Coffee",
    headings: ["The cupping ritual", "Flavour vocabulary"],
    tags: ["tasting", "sourcing"],
    body: `Cupping is the standard way professionals taste and score coffee: grounds are steeped in hot
      water in identical bowls, the crust is broken and skimmed, and tasters slurp spoonfuls to spread the
      liquid across the palate. It isolates the coffee's own character — acidity, body, sweetness,
      aftertaste — from any brewing variable, which is why buyers use it to compare lots.`,
  },
];

export const EVAL_QUERIES: readonly EvalQuery[] = [
  {
    query: "how fine should I grind my coffee",
    relevant: ["grind-size", "burr-vs-blade"],
    rationale:
      "Grind fineness is the direct subject of grind-size; burr-vs-blade is about achieving a consistent grind, which is the same concern. Espresso and French press mention grind but as one variable among many, not the topic.",
  },
  {
    query: "why does my espresso taste bitter",
    relevant: ["espresso-basics", "grind-size"],
    rationale:
      "espresso-basics explains that a slow shot turns bitter (over-extraction); grind-size explains bitterness as over-extraction generally. Roast levels also mention bitterness but attribute it to dark roasting, a different cause than the query's espresso context.",
  },
  {
    query: "immersion brewing methods",
    relevant: ["french-press", "cold-brew"],
    rationale:
      "French press is explicitly immersion; cold brew is also immersion (a long steep). Pour-over is the counterexample — it is percolation, not immersion — so it must NOT be relevant despite being a brewing method.",
  },
  {
    query: "keeping beans fresh",
    relevant: ["storage"],
    rationale:
      "Only storage is about freshness and preventing staling. bean-origin and roast-levels are about beans but not about keeping them fresh.",
  },
  {
    query: "what makes coffee taste different depending on where it is grown",
    relevant: ["bean-origin"],
    rationale:
      "Terroir and origin flavour is exactly bean-origin. roast-levels shapes flavour too but via roasting, not growing location, so it is not the answer to this specific query.",
  },
  {
    query: "milk foam for latte art",
    relevant: ["milk-steaming"],
    rationale: "Only milk-steaming covers microfoam and latte art. No other document touches milk.",
  },
  {
    query: "does water matter for brewing coffee",
    relevant: ["water-quality"],
    rationale: "water-quality is the sole document on water chemistry. Brewing-method docs use water but do not discuss its composition.",
  },
  {
    query: "extraction and flavour",
    relevant: ["grind-size", "espresso-basics", "cold-brew"],
    rationale:
      "Extraction is central to grind-size (surface area drives it), espresso-basics (seconds-long extraction), and cold-brew (long cold extraction selecting for sweetness). This is a broad query with several genuinely relevant docs — a recall test.",
  },
  {
    query: "consistent particle size when grinding",
    relevant: ["burr-vs-blade"],
    rationale:
      "Consistency of particle size is the whole argument of burr-vs-blade. grind-size is about fineness, not consistency, so it is a near-miss that should rank below or outside the relevant set.",
  },
  {
    query: "how professionals score and compare coffee",
    relevant: ["cupping"],
    rationale: "Cupping is the professional scoring ritual — only cupping. bean-origin mentions buyers but the scoring method itself is cupping.",
  },
];
