package ru.miacomsoft.EasyWebServer;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RunProcess {

    private static final String[] WIN_RUNTIME = {"cmd.exe", "/C"};
    private static final String[] OS_LINUX_RUNTIME = {"bash", "-l", "-c" };

    public static boolean isWin() {
        boolean isWin = false;
        String osName = System.getProperty("os.name");
        if (osName.contains("Windows"))
            isWin = true;
        return isWin;
    }

    private static <T> T[] concat(T[] first, T[] second) {
        T[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    public static List<String> exec(File workingDir, boolean flgDebug, String... command) {
        boolean isWin = isWin();
        if (flgDebug) {
            System.out.print("command to run: ");
            for (String s : command) {
                System.out.print(s);
            }
            System.out.print("\n");
        }
        String[] allCommand = null;
        try {
            if (isWin()) {
                allCommand = concat(WIN_RUNTIME, command);
            } else {
                allCommand = concat(OS_LINUX_RUNTIME, command);
            }
            ProcessBuilder pb = new ProcessBuilder(allCommand);
            pb.redirectErrorStream(true);
            if (workingDir != null)
                pb.directory(workingDir);
            Process p = pb.start();
            p.waitFor();
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    p.getInputStream()));
            String _temp = null;
            List<String> line = new ArrayList<String>();
            while ((_temp = in.readLine()) != null) {
                if (flgDebug)
                    System.out.println("temp line: " + _temp);
                line.add(_temp);
            }
            if (flgDebug)
                System.out.println("result after command: " + line);
            return line;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
