package forge.view;

import java.io.File;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.lang3.time.StopWatch;

import forge.LobbyPlayer;
import forge.ai.AIOption;
import forge.deck.Deck;
import forge.deck.DeckGroup;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameLogEntryType;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.tournament.system.AbstractTournament;
import forge.gamemodes.tournament.system.TournamentBracket;
import forge.gamemodes.tournament.system.TournamentPairing;
import forge.gamemodes.tournament.system.TournamentPlayer;
import forge.gamemodes.tournament.system.TournamentRoundRobin;
import forge.gamemodes.tournament.system.TournamentSwiss;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.Lang;
import forge.util.TextUtil;
import forge.util.WordUtil;
import forge.util.storage.IStorage;

public class SimulateMatch {

    // ---------------------------------------------------------------------------
    // Result record — returned by every simulateSingleMatch call so callers can
    // aggregate statistics across many games.
    // ---------------------------------------------------------------------------
    public static class GameResult {
        /** Name of the winning LobbyPlayer, or null on a draw. */
        public final String winnerName;
        public final boolean isDraw;
        /** Wall-clock milliseconds the game took. */
        public final long durationMs;
        /** Total turn number at game end (as reported by GameOutcome). */
        public final int turnCount;

        public GameResult(String winnerName, boolean isDraw, long durationMs, int turnCount) {
            this.winnerName = winnerName;
            this.isDraw = isDraw;
            this.durationMs = durationMs;
            this.turnCount = turnCount;
        }
    }

    // ---------------------------------------------------------------------------
    // Entry point — called from Main with "sim …" args
    // ---------------------------------------------------------------------------
    public static void simulate(String[] args) {
        FModel.initialize(null, null);

        System.out.println("Simulation mode");
        if (args.length < 4) {
            argumentHelp();
            return;
        }

        final Map<String, List<String>> params = new HashMap<>();
        List<String> options = null;

        for (int i = 1; i < args.length; i++) {
            // "sim" occupies slot 0
            final String a = args[i];
            if (a.charAt(0) == '-') {
                if (a.length() < 2) {
                    System.err.println("Error at argument " + a);
                    argumentHelp();
                    return;
                }
                options = new ArrayList<>();
                params.put(a.substring(1), options);
            } else if (options != null) {
                options.add(a);
            } else {
                System.err.println("Illegal parameter usage");
                return;
            }
        }

        // ---- basic flags -------------------------------------------------------
        int nGames = 1;
        if (params.containsKey("n")) {
            nGames = Integer.parseInt(params.get("n").get(0));
        }

        int matchSize = 0;
        if (params.containsKey("m")) {
            matchSize = Integer.parseInt(params.get("m").get(0));
        }

        boolean outputGamelog = !params.containsKey("q");

        GameType type = GameType.Constructed;
        if (params.containsKey("f")) {
            type = GameType.valueOf(WordUtil.capitalize(params.get("f").get(0)));
        }

        GameRules rules = new GameRules(type);
        rules.setAppliedVariants(EnumSet.of(type));

        if (matchSize != 0) {
            rules.setGamesPerMatch(matchSize);
        }

        // ---- NEW: -s flag — which 0-based player indices use simulation AI ------
        // Examples:
        //   -s 0        → player 1 uses simulation, rest use heuristics
        //   -s 0 1      → both players use simulation
        //   (omitted)   → all players use heuristics (original behaviour)
        Set<Integer> simIndices = new HashSet<>();
        if (params.containsKey("s")) {
            for (String idx : params.get("s")) {
                simIndices.add(Integer.parseInt(idx));
            }
        }

        // ---- tournament shortcut (delegates, no comparison output) --------------
        if (params.containsKey("t")) {
            simulateTournament(params, rules, outputGamelog, simIndices);
            System.out.flush();
            return;
        }

        // ---- build player list --------------------------------------------------
        List<RegisteredPlayer> pp = new ArrayList<>();
        List<String> playerLabels = new ArrayList<>();   // human-readable labels for summary
        StringBuilder sb = new StringBuilder();

        int i = 1;
        if (params.containsKey("d")) {
            for (String deck : params.get("d")) {
                Deck d = deckFromCommandLineParameter(deck, type);
                if (d == null) {
                    System.out.println(TextUtil.concatNoSpace("Could not load deck - ", deck, ", match cannot start"));
                    return;
                }
                if (i > 1) sb.append(" vs ");

                boolean useSim = simIndices.contains(i - 1);
                String tag  = useSim ? "[Sim]" : "[Heuristic]";
                String name = TextUtil.concatNoSpace(tag, "Ai(", String.valueOf(i), ")-", d.getName());
                sb.append(name);
                playerLabels.add(name);

                RegisteredPlayer rp;
                if (type.equals(GameType.Commander)) {
                    rp = RegisteredPlayer.forCommander(d);
                } else {
                    rp = new RegisteredPlayer(d);
                }

                // Choose the correct AI factory depending on mode
                if (useSim) {
                    rp.setPlayer(GamePlayerUtil.createAiPlayer(name, i - 1, 0,
                            EnumSet.of(AIOption.USE_SIMULATION)));
                } else {
                    rp.setPlayer(GamePlayerUtil.createAiPlayer(name, i - 1));
                }
                pp.add(rp);
                i++;
            }
        }

        if (params.containsKey("c")) {
            rules.setSimTimeout(Integer.parseInt(params.get("c").get(0)));
        }

        sb.append(" - ").append(Lang.nounWithNumeral(nGames, "game")).append(" of ").append(type);
        System.out.println(sb.toString());

        // ---- announce AI modes if comparison is active -------------------------
        if (!simIndices.isEmpty() && simIndices.size() < pp.size()) {
            System.out.println("=== AI COMPARISON MODE: Heuristic vs Simulation ===");
        }

        // ---- run games and collect results -------------------------------------
        List<GameResult> results = new ArrayList<>();
        Match mc = new Match(rules, pp, "Test");

        if (matchSize != 0) {
            int iGame = 0;
            while (!mc.isMatchOver()) {
                results.add(simulateSingleMatch(mc, iGame, outputGamelog));
                iGame++;
            }
        } else {
            for (int iGame = 0; iGame < nGames; iGame++) {
                results.add(simulateSingleMatch(mc, iGame, outputGamelog));
            }
        }

        // ---- print comparison summary if more than one game was played ----------
        if (results.size() > 1) {
            printMatchSummary(playerLabels, results);
        }

        System.out.flush();
    }

    // ---------------------------------------------------------------------------
    // Summary printer — shown after multi-game runs
    // ---------------------------------------------------------------------------
    private static void printMatchSummary(List<String> playerLabels, List<GameResult> results) {
        int total  = results.size();
        int draws  = 0;
        long totalMs    = 0;
        long totalTurns = 0;

        // wins[i] counts wins for playerLabels.get(i)
        int[] wins = new int[playerLabels.size()];

        for (GameResult r : results) {
            totalMs    += r.durationMs;
            totalTurns += r.turnCount;
            if (r.isDraw) {
                draws++;
            } else {
                for (int i = 0; i < playerLabels.size(); i++) {
                    if (playerLabels.get(i).equals(r.winnerName)) {
                        wins[i]++;
                        break;
                    }
                }
            }
        }

        int decided = total - draws;
        double avgMs    = total > 0 ? (double) totalMs    / total : 0;
        double avgTurns = total > 0 ? (double) totalTurns / total : 0;

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║              AI COMPARISON SUMMARY                      ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.printf( "║  Total games : %-5d                                    ║%n", total);
        System.out.printf( "║  Draws       : %-5d                                    ║%n", draws);
        System.out.printf( "║  Avg turns   : %-6.1f                                   ║%n", avgTurns);
        System.out.printf( "║  Avg time    : %-8.0f ms                               ║%n", avgMs);
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.println("║  Player Results:                                         ║");

        for (int i = 0; i < playerLabels.size(); i++) {
            double winPct = decided > 0 ? 100.0 * wins[i] / decided : 0.0;
            // Truncate label to fit in the box
            String label = playerLabels.get(i);
            if (label.length() > 36) label = label.substring(0, 33) + "...";
            System.out.printf("║    %-36s  %3d wins  (%5.1f%%)  ║%n",
                    label, wins[i], winPct);
        }

        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    // ---------------------------------------------------------------------------
    // Argument help
    // ---------------------------------------------------------------------------
    private static void argumentHelp() {
        System.out.println("Syntax: forge.exe sim -d <deck1[.dck]> ... <deckX[.dck]> -D [D] -n [N] -m [M] -t [T] -p [P] -f [F] -s [S...] -q");
        System.out.println("\tsim    - simulation mode");
        System.out.println("\t-d     - space-separated deck names or .dck filenames");
        System.out.println("\t-D [D] - absolute directory to load .dck decks from");
        System.out.println("\t-n [N] - number of games (default 1)");
        System.out.println("\t-m [M] - best-of-M match (overrides -n)");
        System.out.println("\t-t [T] - tournament type: Bracket | RoundRobin | Swiss");
        System.out.println("\t-p [P] - players per match in tournament mode (default 2)");
        System.out.println("\t-f [F] - game format (default: Constructed)");
        System.out.println("\t-c [S] - clock: max seconds before draw (default 120)");
        System.out.println("\t-s [S] - 0-based player indices that use Simulation AI");
        System.out.println("\t         e.g. -s 0     → player 1 uses simulation, rest use heuristics");
        System.out.println("\t         e.g. -s 0 1   → all players use simulation");
        System.out.println("\t         Omitting -s keeps original heuristic AI for all players.");
        System.out.println("\t-q     - quiet: print only results, not the full game log");
        System.out.println();
        System.out.println("AI Comparison example (same deck, 100 games):");
        System.out.println("  java -jar forge.jar sim -d MyDeck MyDeck -s 0 -n 100 -q");
        System.out.println("  → [Heuristic]Ai(2) vs [Sim]Ai(1), same deck, quiet output, summary at end.");
    }

    // ---------------------------------------------------------------------------
    // Run one game and return its result.
    // (signature changed: now returns GameResult instead of void)
    // ---------------------------------------------------------------------------
    public static GameResult simulateSingleMatch(final Match mc, int iGame, boolean outputGamelog) {
        final StopWatch sw = new StopWatch();
        sw.start();

        final Game g1 = mc.createGame();
        try {
            TimeLimitedCodeBlock.runWithTimeout(() -> {
                mc.startGame(g1);
                sw.stop();
            }, mc.getRules().getSimTimeout(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            System.out.println("Stopping slow match as draw");
        } catch (Exception | StackOverflowError e) {
            e.printStackTrace();
        } finally {
            if (sw.isStarted()) sw.stop();
            if (!g1.isGameOver()) g1.setGameOver(GameEndReason.Draw);
        }

        List<GameLogEntry> log;
        if (outputGamelog) {
            log = g1.getGameLog().getLogEntries(null);
        } else {
            log = g1.getGameLog().getLogEntries(GameLogEntryType.MATCH_RESULTS);
        }
        Collections.reverse(log);
        for (GameLogEntry l : log) {
            System.out.println(l);
        }

        long durationMs = sw.getTime();
        boolean isDraw  = g1.getOutcome().isDraw();
        int turnCount   = g1.getOutcome().getLastTurnNumber();
        String winner   = null;

        if (isDraw) {
            System.out.printf("%nGame Result: Game %d ended in a Draw! Took %d ms.%n", 1 + iGame, durationMs);
        } else {
            winner = g1.getOutcome().getWinningLobbyPlayer().getName();
            System.out.printf("%nGame Result: Game %d ended in %d ms. %s has won!%n%n", 1 + iGame, durationMs, winner);
        }

        return new GameResult(winner, isDraw, durationMs, turnCount);
    }

    // ---------------------------------------------------------------------------
    // Tournament runner — updated to accept simIndices and pass them through.
    // ---------------------------------------------------------------------------
    private static void simulateTournament(Map<String, List<String>> params,
                                           GameRules rules,
                                           boolean outputGamelog,
                                           Set<Integer> simIndices) {
        String tournament = params.get("t").get(0);
        AbstractTournament tourney = null;
        int matchPlayers = params.containsKey("p") ? Integer.parseInt(params.get("p").get(0)) : 2;

        DeckGroup deckGroup = new DeckGroup("SimulatedTournament");
        List<TournamentPlayer> players = new ArrayList<>();
        int numPlayers = 0;

        if (params.containsKey("d")) {
            for (String deck : params.get("d")) {
                Deck d = deckFromCommandLineParameter(deck, rules.getGameType());
                if (d == null) {
                    System.out.println(TextUtil.concatNoSpace("Could not load deck - ", deck, ", match cannot start"));
                    return;
                }
                deckGroup.addAiDeck(d);
                boolean useSim = simIndices.contains(numPlayers);
                String tag = useSim ? "[Sim]" : "[Heuristic]";
                String name = TextUtil.concatNoSpace(tag, d.getName());
                // Note: tournament player creation doesn't easily support per-player AI options
                // because AbstractTournament.registerTournamentPlayers rebuilds RegisteredPlayers
                // from the deck group. We tag the name so it's visible in output.
                players.add(new TournamentPlayer(GamePlayerUtil.createAiPlayer(name, 0), numPlayers));
                numPlayers++;
            }
        }

        if (params.containsKey("D")) {
            String foldName = params.get("D").get(0);
            File folder = new File(foldName);
            if (!folder.isDirectory()) {
                System.out.println("Directory not found - " + foldName);
            } else {
                for (File deck : folder.listFiles((dir, name) -> name.endsWith(".dck"))) {
                    Deck d = DeckSerializer.fromFile(deck);
                    if (d == null) {
                        System.out.println(TextUtil.concatNoSpace("Could not load deck - ", deck.getName(), ", match cannot start"));
                        return;
                    }
                    deckGroup.addAiDeck(d);
                    players.add(new TournamentPlayer(GamePlayerUtil.createAiPlayer(d.getName(), 0), numPlayers));
                    numPlayers++;
                }
            }
        }

        if (numPlayers == 0) {
            System.out.println("No decks/Players found. Please try again.");
            return;
        }

        if ("bracket".equalsIgnoreCase(tournament)) {
            tourney = new TournamentBracket(players, matchPlayers);
        } else if ("roundrobin".equalsIgnoreCase(tournament)) {
            tourney = new TournamentRoundRobin(players, matchPlayers);
        } else if ("swiss".equalsIgnoreCase(tournament)) {
            tourney = new TournamentSwiss(players, matchPlayers);
        }
        if (tourney == null) {
            System.out.println("Failed to initialize tournament, bailing out");
            return;
        }

        tourney.initializeTournament();

        String lastWinner = "";
        int curRound = 0;
        System.out.println(TextUtil.concatNoSpace("Starting a ", tournament, " tournament with ",
                String.valueOf(numPlayers), " players over ",
                String.valueOf(tourney.getTotalRounds()), " rounds"));

        while (!tourney.isTournamentOver()) {
            if (tourney.getActiveRound() != curRound) {
                if (curRound != 0) {
                    System.out.println(TextUtil.concatNoSpace("End Round - ", String.valueOf(curRound)));
                }
                curRound = tourney.getActiveRound();
                System.out.println();
                System.out.println(TextUtil.concatNoSpace("Round ", String.valueOf(curRound), " Pairings:"));
                for (TournamentPairing pairing : tourney.getActivePairings()) {
                    System.out.println(pairing.outputHeader());
                }
                System.out.println();
            }

            TournamentPairing pairing = tourney.getNextPairing();
            List<RegisteredPlayer> regPlayers = AbstractTournament.registerTournamentPlayers(pairing, deckGroup);

            StringBuilder sb = new StringBuilder();
            sb.append("Round ").append(tourney.getActiveRound()).append(" - ");
            sb.append(pairing.outputHeader());
            System.out.println(sb.toString());

            if (!pairing.isBye()) {
                Match mc = new Match(rules, regPlayers, "TourneyMatch");
                int exceptions = 0;
                int iGame = 0;
                while (!mc.isMatchOver()) {
                    try {
                        simulateSingleMatch(mc, iGame, outputGamelog);
                        iGame++;
                    } catch (Exception e) {
                        exceptions++;
                        System.out.println(e.toString());
                        if (exceptions > 5) {
                            System.out.println("Exceeded number of exceptions thrown. Abandoning match...");
                            break;
                        } else {
                            System.out.println("Game threw exception. Abandoning game and continuing...");
                        }
                    }
                }
                LobbyPlayer winner = mc.getWinner().getPlayer();
                for (TournamentPlayer tp : pairing.getPairedPlayers()) {
                    if (winner.equals(tp.getPlayer())) {
                        pairing.setWinner(tp);
                        lastWinner = winner.getName();
                        System.out.println(TextUtil.concatNoSpace("Match Winner - ", lastWinner, "!"));
                        System.out.println();
                        break;
                    }
                }
            }
            tourney.reportMatchCompletion(pairing);
        }
        tourney.outputTournamentResults();
    }

    // ---------------------------------------------------------------------------
    // Off-thread helper (stub — unchanged from original)
    // ---------------------------------------------------------------------------
    public static Match simulateOffthreadGame(List<Deck> decks, GameType format, int games) {
        return null;
    }

    // ---------------------------------------------------------------------------
    // Deck loader (unchanged from original)
    // ---------------------------------------------------------------------------
    private static Deck deckFromCommandLineParameter(String deckname, GameType type) {
        int dotpos = deckname.lastIndexOf('.');
        if (dotpos > 0 && dotpos == deckname.length() - 4) {
            String baseDir = type.equals(GameType.Commander)
                    ? ForgeConstants.DECK_COMMANDER_DIR
                    : ForgeConstants.DECK_CONSTRUCTED_DIR;
            File f = new File(baseDir + deckname);
            if (!f.exists()) {
                System.out.println("No deck found in " + baseDir);
            }
            return DeckSerializer.fromFile(f);
        }

        IStorage<Deck> deckStore;
        if (type.equals(GameType.Commander)) {
            deckStore = FModel.getDecks().getCommander();
        } else {
            deckStore = FModel.getDecks().getConstructed();
        }
        return deckStore.get(deckname);
    }
}
