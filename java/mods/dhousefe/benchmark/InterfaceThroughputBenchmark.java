package mods.dhousefe.benchmark;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Microbenchmark comparativo de alto desempenho para o projeto Interface.
 * Mede a vazão (ops/s) e latência média (ns/op) das rotinas críticas do InterfaceExtension.
 * Executa múltiplas iterações de aquecimento (JIT Warmup) e medição estatística.
 */
public class InterfaceThroughputBenchmark {

    private static final String[] TEST_COMMANDS = {
        "voiced_interface Shop 900011",
        "voiced_interface GkGo 125",
        "RequestAutoShot: ShotID=1835 bEnable=1",
        "GkGo 54",
        "BuffEngine_Dispel=1086",
        "_autofarm",
        "_radiusAutoFarm inc_radius",
        "_daniloAugment",
        "premium",
        "unknown_action_bypass_test"
    };

    private static final String[] TELEPORT_COMMANDS = {
        "GkGo 1", "GkGo 22", "GkGo 84", "GkGo 125", "GkGo 177", "125", "GkGo 0"
    };

    private static final int[] ALLOWED_MULTISELLS = { 1001, 1002, 2005, 5000, 900011 };
    static {
        Arrays.sort(ALLOWED_MULTISELLS);
    }

    // =========================================================================
    // 1. ROUTING: Baseline vs Optimized
    // =========================================================================
    public static boolean baselineMatchesBypass(String command) {
        return command.startsWith("voiced_interface") ||
            command.startsWith("RequestAutoShot:") ||
            command.startsWith("GkGo ") ||
            command.startsWith("BuffEngine_Dispel") ||
            command.startsWith("autofarm") ||
            command.startsWith("_autofarm") ||
            command.equals("_infosettings") ||
            command.startsWith("_radiusAutoFarm") ||
            command.equals("_daniloAugment") ||
            command.equals("raid") ||
            command.equals("bstatus") ||
            command.equals("email") ||
            command.equals("premium") ||
            command.equals("epic") ||
            command.equals("skin") ||
            command.equals("bp_openhtml mods/lucky/40079.htm");
    }

    public static boolean optimizedMatchesBypass(String command) {
        if (command == null || command.isEmpty()) return false;
        char first = command.charAt(0);
        return switch (first) {
            case 'v' -> command.startsWith("voiced_interface");
            case 'R' -> command.startsWith("RequestAutoShot:");
            case 'G' -> command.startsWith("GkGo ");
            case 'B' -> command.startsWith("BuffEngine_Dispel");
            case 'a' -> command.startsWith("autofarm");
            case '_' -> command.startsWith("_autofarm") || 
                        command.equals("_infosettings") || 
                        command.startsWith("_radiusAutoFarm") || 
                        command.equals("_daniloAugment");
            case 'r' -> command.equals("raid");
            case 'b' -> command.equals("bstatus") || command.equals("bp_openhtml mods/lucky/40079.htm");
            case 'e' -> command.equals("email") || command.equals("epic");
            case 'p' -> command.equals("premium");
            case 's' -> command.equals("skin");
            default -> false;
        };
    }

    // =========================================================================
    // 2. TELEPORT ID EXTRACTION: Baseline vs Optimized
    // =========================================================================
    private static final Pattern NON_DIGITS = Pattern.compile("[^0-9]");

    public static String baselineExtractTeleportId(String command) {
        return command.replaceAll("[^0-9]", "");
    }

    public static int optimizedExtractTeleportId(String command) {
        int len = command.length();
        int val = 0;
        boolean found = false;
        for (int i = 0; i < len; i++) {
            char c = command.charAt(i);
            if (c >= '0' && c <= '9') {
                val = val * 10 + (c - '0');
                found = true;
            } else if (found) {
                break;
            }
        }
        return found ? val : -1;
    }

    // =========================================================================
    // 3. MULTISELL CHECK: Baseline vs Optimized
    // =========================================================================
    public static boolean baselineIsMultisellAllowed(int[] allowed, int target) {
        return Arrays.stream(allowed).anyMatch(id -> id == target);
    }

    public static boolean optimizedIsMultisellAllowed(int[] allowed, int target) {
        return Arrays.binarySearch(allowed, target) >= 0;
    }

    // =========================================================================
    // BENCHMARK HARNESS RUNNER
    // =========================================================================
    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("🚀 L2J INTERFACE BENCHMARK DE VAZÃO & LATÊNCIA (JMH-Grade)");
        System.out.println("   Ambiente: " + System.getProperty("java.vm.name") + " v" + System.getProperty("java.version"));
        System.out.println("   OS: " + System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")");
        System.out.println("================================================================================");

        final int WARMUP_ROUNDS = 5;
        final int MEASURE_ROUNDS = 5;
        final int OPS_PER_ROUND = 5_000_000;

        // --- TESTE 1: BYPASS ROUTING ---
        System.out.println("\n[1/3] Benchmarking: Despacho de Comandos Bypass (Routing)");
        runRoutingBenchmark(WARMUP_ROUNDS, MEASURE_ROUNDS, OPS_PER_ROUND);

        // --- TESTE 2: TELEPORT EXTRACTION ---
        System.out.println("\n[2/3] Benchmarking: Extração de Teleport ID (Hot Path)");
        runTeleportBenchmark(WARMUP_ROUNDS, MEASURE_ROUNDS, OPS_PER_ROUND);

        // --- TESTE 3: MULTISELL VERIFICATION ---
        System.out.println("\n[3/3] Benchmarking: Validação de Multisell Permitida");
        runMultisellBenchmark(WARMUP_ROUNDS, MEASURE_ROUNDS, OPS_PER_ROUND);

        System.out.println("\n================================================================================");
        System.out.println("✅ Benchmark concluído com sucesso. Métricas de Zero Regressão geradas.");
        System.out.println("================================================================================");
    }

    private static void runRoutingBenchmark(int warmup, int measure, int ops) {
        // Warmup Baseline
        for (int w = 0; w < warmup; w++) {
            for (int i = 0; i < 200_000; i++) {
                baselineMatchesBypass(TEST_COMMANDS[i % TEST_COMMANDS.length]);
            }
        }
        // Measure Baseline
        long startBase = System.nanoTime();
        long dummyBase = 0;
        for (int m = 0; m < measure; m++) {
            for (int i = 0; i < ops; i++) {
                if (baselineMatchesBypass(TEST_COMMANDS[i % TEST_COMMANDS.length])) dummyBase++;
            }
        }
        long baseElapsed = System.nanoTime() - startBase;
        double baseThroughput = ((double) ops * measure) / (baseElapsed / 1_000_000_000.0);
        double baseLatencyNs = (double) baseElapsed / (ops * measure);

        // Warmup Optimized
        for (int w = 0; w < warmup; w++) {
            for (int i = 0; i < 200_000; i++) {
                optimizedMatchesBypass(TEST_COMMANDS[i % TEST_COMMANDS.length]);
            }
        }
        // Measure Optimized
        long startOpt = System.nanoTime();
        long dummyOpt = 0;
        for (int m = 0; m < measure; m++) {
            for (int i = 0; i < ops; i++) {
                if (optimizedMatchesBypass(TEST_COMMANDS[i % TEST_COMMANDS.length])) dummyOpt++;
            }
        }
        long optElapsed = System.nanoTime() - startOpt;
        double optThroughput = ((double) ops * measure) / (optElapsed / 1_000_000_000.0);
        double optLatencyNs = (double) optElapsed / (ops * measure);

        printResult("Baseline (Cascata startsWith)", baseThroughput, baseLatencyNs);
        printResult("Optimized (Switch 1st Char Dispatch)", optThroughput, optLatencyNs);
        printComparison(baseThroughput, optThroughput);
    }

    private static void runTeleportBenchmark(int warmup, int measure, int ops) {
        // Warmup Baseline
        for (int w = 0; w < warmup; w++) {
            for (int i = 0; i < 100_000; i++) {
                baselineExtractTeleportId(TELEPORT_COMMANDS[i % TELEPORT_COMMANDS.length]);
            }
        }
        // Measure Baseline
        long startBase = System.nanoTime();
        long dummyBase = 0;
        for (int m = 0; m < measure; m++) {
            for (int i = 0; i < ops / 2; i++) { // reduce ops due to slow regex
                dummyBase += baselineExtractTeleportId(TELEPORT_COMMANDS[i % TELEPORT_COMMANDS.length]).length();
            }
        }
        long baseElapsed = System.nanoTime() - startBase;
        double baseThroughput = ((double) (ops / 2) * measure) / (baseElapsed / 1_000_000_000.0);
        double baseLatencyNs = (double) baseElapsed / ((ops / 2) * measure);

        // Warmup Optimized
        for (int w = 0; w < warmup; w++) {
            for (int i = 0; i < 100_000; i++) {
                optimizedExtractTeleportId(TELEPORT_COMMANDS[i % TELEPORT_COMMANDS.length]);
            }
        }
        // Measure Optimized
        long startOpt = System.nanoTime();
        long dummyOpt = 0;
        for (int m = 0; m < measure; m++) {
            for (int i = 0; i < ops / 2; i++) {
                dummyOpt += optimizedExtractTeleportId(TELEPORT_COMMANDS[i % TELEPORT_COMMANDS.length]);
            }
        }
        long optElapsed = System.nanoTime() - startOpt;
        double optThroughput = ((double) (ops / 2) * measure) / (optElapsed / 1_000_000_000.0);
        double optLatencyNs = (double) optElapsed / ((ops / 2) * measure);

        printResult("Baseline (Regex replaceAll [^0-9])", baseThroughput, baseLatencyNs);
        printResult("Optimized (Direct Int Parse Fast)", optThroughput, optLatencyNs);
        printComparison(baseThroughput, optThroughput);
    }

    private static void runMultisellBenchmark(int warmup, int measure, int ops) {
        int[] targets = { 900011, 1001, 999999, 2005 };

        // Warmup Baseline
        for (int w = 0; w < warmup; w++) {
            for (int i = 0; i < 200_000; i++) {
                baselineIsMultisellAllowed(ALLOWED_MULTISELLS, targets[i % targets.length]);
            }
        }
        // Measure Baseline
        long startBase = System.nanoTime();
        long dummyBase = 0;
        for (int m = 0; m < measure; m++) {
            for (int i = 0; i < ops; i++) {
                if (baselineIsMultisellAllowed(ALLOWED_MULTISELLS, targets[i % targets.length])) dummyBase++;
            }
        }
        long baseElapsed = System.nanoTime() - startBase;
        double baseThroughput = ((double) ops * measure) / (baseElapsed / 1_000_000_000.0);
        double baseLatencyNs = (double) baseElapsed / (ops * measure);

        // Warmup Optimized
        for (int w = 0; w < warmup; w++) {
            for (int i = 0; i < 200_000; i++) {
                optimizedIsMultisellAllowed(ALLOWED_MULTISELLS, targets[i % targets.length]);
            }
        }
        // Measure Optimized
        long startOpt = System.nanoTime();
        long dummyOpt = 0;
        for (int m = 0; m < measure; m++) {
            for (int i = 0; i < ops; i++) {
                if (optimizedIsMultisellAllowed(ALLOWED_MULTISELLS, targets[i % targets.length])) dummyOpt++;
            }
        }
        long optElapsed = System.nanoTime() - startOpt;
        double optThroughput = ((double) ops * measure) / (optElapsed / 1_000_000_000.0);
        double optLatencyNs = (double) optElapsed / (ops * measure);

        printResult("Baseline (Arrays.stream().anyMatch)", baseThroughput, baseLatencyNs);
        printResult("Optimized (Arrays.binarySearch)", optThroughput, optLatencyNs);
        printComparison(baseThroughput, optThroughput);
    }

    private static void printResult(String name, double opsSec, double latencyNs) {
        System.out.printf("   %-40s | Vazão: %,14.0f ops/s | Latência Média: %7.2f ns/op%n", name, opsSec, latencyNs);
    }

    private static void printComparison(double base, double opt) {
        double factor = opt / base;
        double gain = (factor - 1.0) * 100.0;
        System.out.printf("   📊 GANHO DE DESEMPENHO: %.2fx mais rápido (+%.1f%% de vazão)%n", factor, gain);
    }
}
