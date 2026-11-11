package dev.rusteq.audio;

public final class ProcessProbe {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "success": System.out.print("ready\n"); break;
            case "failure": System.exit(3); break;
            case "sleep":
                if (args.length > 1) {
                    Class<?> type = Class.forName("java.lang.ProcessHandle");
                    Object handle = type.getMethod("current").invoke(null);
                    String pid = type.getMethod("pid").invoke(handle).toString();
                    try (java.io.FileOutputStream file = new java.io.FileOutputStream(args[1])) { file.write(pid.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                }
                Thread.sleep(30000); break;
            case "large": for (int i = 0; i < 4096; i++) System.out.print('x'); break;
            default: throw new IllegalArgumentException();
        }
    }
}
