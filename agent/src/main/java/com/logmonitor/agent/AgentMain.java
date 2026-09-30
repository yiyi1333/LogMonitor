package com.logmonitor.agent;

import com.logmonitor.agent.AgentModels.EnrollRequest;
import com.logmonitor.agent.AgentModels.EnrollResponse;
import com.logmonitor.agent.AgentModels.LocalConfig;
import com.logmonitor.agent.AgentModels.RootRequest;
import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

public final class AgentMain {
    static final String VERSION = "1.1.1";
    private static final Path DEFAULT_CONFIG = Paths.get("/etc/logmonitor-agent/agent.json");
    private static final Path DEFAULT_DATA = Paths.get("/var/lib/logmonitor-agent");

    private AgentMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0])) {
            usage();
            return;
        }
        String command = args[0];
        Path config = option(args, "--config", DEFAULT_CONFIG);
        Path data = option(args, "--data", DEFAULT_DATA);
        if ("configure".equals(command)) configure(config, contains(args, "--allow-http"));
        else if ("run".equals(command)) run(config, data, contains(args, "--once"));
        else if ("status".equals(command)) status(config, data);
        else if ("check".equals(command)) {
            try { check(config, data); }
            catch (Exception exception) {
                System.err.println("Agent 启动预检失败: " + exception.getMessage());
                System.exit(1);
            }
        }
        else throw new IllegalArgumentException("未知命令: " + command);
    }

    private static void configure(Path configPath, boolean allowHttp) throws Exception {
        if (Files.exists(configPath)) {
            throw new IllegalStateException("配置已存在；允许根固定，变更时请先在中心撤销 Agent 并删除本地配置");
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        String defaultHost = InetAddress.getLocalHost().getHostName();
        String server = prompt(reader, "LogMonitor 中心地址", null);
        AgentHttpClient.validateServerUrl(server, allowHttp);
        if (server.regionMatches(true, 0, "http://", 0, 7) && allowHttp) {
            System.err.println("警告：已允许远程 HTTP，注册密码、Agent Token 和日志数据将以明文传输");
        }
        String username = prompt(reader, "用户名", null);
        char[] passwordChars = password(reader);
        String name = prompt(reader, "Agent 名称", defaultHost);
        String displayAddress = prompt(reader, "展示 IP 地址", detectAddress());
        String rootsValue = prompt(reader, "允许根目录（多个以逗号分隔）", null);

        EnrollRequest request = new EnrollRequest();
        request.username = username;
        request.password = new String(passwordChars);
        Arrays.fill(passwordChars, '\0');
        request.name = name;
        request.hostName = defaultHost;
        request.displayAddress = displayAddress;
        request.version = VERSION;
        List<String> realRoots = new ArrayList<String>();
        for (String value : rootsValue.split(",")) {
            Path path = Paths.get(value.trim());
            if (!path.isAbsolute() || !Files.isDirectory(path) || !Files.isReadable(path)) {
                throw new IllegalArgumentException("允许根目录不存在、不可读或不是绝对路径: " + value);
            }
            Path real = path.toRealPath();
            request.roots.add(new RootRequest(path.normalize().toString(), real.toString()));
            realRoots.add(real.toString());
        }
        EnrollResponse response;
        try { response = new AgentHttpClient(server, null, allowHttp).enroll(request); }
        finally { request.password = null; }

        LocalConfig local = new LocalConfig();
        local.serverUrl = server;
        local.allowHttp = allowHttp;
        local.agentId = response.agentId;
        local.agentUuid = response.agentUuid;
        local.token = response.token;
        local.agentName = name;
        local.hostName = defaultHost;
        local.displayAddress = displayAddress;
        local.configRevision = response.configRevision;
        local.pollIntervalSeconds = response.pollIntervalSeconds;
        local.maxBatchBytes = response.maxBatchBytes;
        local.spoolLimitBytes = response.spoolLimitBytes;
        local.allowedRoots = realRoots;
        AgentFiles.writeAtomic(configPath, local);
        AgentFiles.ownerOnly(configPath);
        System.out.println("Agent 注册成功: " + response.agentUuid);
    }

    private static void run(final Path config, final Path data, boolean once) throws Exception {
        if (!Files.exists(config)) throw new IllegalStateException("Agent 尚未配置: " + config);
        final AgentRuntime runtime = new AgentRuntime(config, data);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() { public void run() { runtime.close(); }}));
        if (once) { runtime.runOnce(); runtime.close(); }
        else runtime.run();
    }

    static void check(Path config, Path data) throws Exception {
        LocalConfig local = validateLocalConfig(config, data);
        new AgentHttpClient(local.serverUrl, local.token, local.allowHttp).configuration(0);
        System.out.println("Agent 配置检查通过: " + config);
        System.out.println("中心服务器连接成功: " + local.serverUrl);
    }

    static LocalConfig validateLocalConfig(Path config, Path data) throws Exception {
        if (!Files.isRegularFile(config) || !Files.isReadable(config)) {
            throw new IllegalStateException("配置文件不存在或不可读: " + config);
        }
        LocalConfig local = AgentFiles.read(config, LocalConfig.class);
        required(local.serverUrl, "serverUrl");
        AgentHttpClient.validateServerUrl(local.serverUrl, local.allowHttp);
        if (local.agentId <= 0) throw new IllegalArgumentException("agentId 必须大于 0");
        required(local.agentUuid, "agentUuid");
        try { UUID.fromString(local.agentUuid); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("agentUuid 格式无效"); }
        required(local.token, "token");
        if (local.token.length() < 32) throw new IllegalArgumentException("token 长度无效");
        required(local.agentName, "agentName");
        required(local.hostName, "hostName");
        if (local.configRevision < 0) throw new IllegalArgumentException("configRevision 不能小于 0");
        if (local.pollIntervalSeconds <= 0) throw new IllegalArgumentException("pollIntervalSeconds 必须大于 0");
        if (local.maxBatchBytes <= 0) throw new IllegalArgumentException("maxBatchBytes 必须大于 0");
        if (local.spoolLimitBytes <= 0) throw new IllegalArgumentException("spoolLimitBytes 必须大于 0");
        if (local.allowedRoots == null || local.allowedRoots.isEmpty()) {
            throw new IllegalArgumentException("allowedRoots 不能为空");
        }
        for (String value : local.allowedRoots) {
            required(value, "allowedRoots");
            Path root = Paths.get(value);
            if (!root.isAbsolute() || !Files.isDirectory(root) || !Files.isReadable(root)) {
                throw new IllegalArgumentException("允许根目录不存在、不可读或不是绝对路径: " + value);
            }
            root.toRealPath();
        }
        if (!Files.isDirectory(data) || !Files.isReadable(data) || !Files.isWritable(data)) {
            throw new IllegalArgumentException("数据目录不存在、不可读或不可写: " + data);
        }
        return local;
    }

    private static void required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " 不能为空");
    }

    private static void status(Path config, Path data) throws Exception {
        LocalConfig local = AgentFiles.read(config, LocalConfig.class);
        long spool = directoryBytes(data.resolve("spool"));
        System.out.println("Agent: " + local.agentName + " (#" + local.agentId + ", " + local.agentUuid + ")");
        System.out.println("Center: " + local.serverUrl);
        System.out.println("Address: " + (local.displayAddress == null ? local.hostName : local.displayAddress));
        System.out.println("Config revision: " + local.configRevision);
        System.out.println("Spool: " + spool + " / " + local.spoolLimitBytes + " bytes");
        try {
            new AgentHttpClient(local.serverUrl, local.token, local.allowHttp).configuration(local.configRevision);
            System.out.println("Connection: connected");
        } catch (Exception exception) {
            System.out.println("Connection: unavailable (" + exception.getMessage() + ")");
        }
    }

    private static long directoryBytes(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) return 0;
        final long[] total = {0};
        java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(directory);
        try { for (Path path : stream) if (Files.isRegularFile(path)) total[0] += Files.size(path); }
        finally { stream.close(); }
        return total[0];
    }

    private static String prompt(BufferedReader reader, String label, String fallback) throws Exception {
        System.out.print(label + (fallback == null ? "" : " [" + fallback + "]") + ": ");
        String value = reader.readLine();
        value = value == null ? "" : value.trim();
        if (value.isEmpty()) value = fallback;
        if (value == null || value.isEmpty()) throw new IllegalArgumentException(label + "不能为空");
        return value;
    }

    private static char[] password(BufferedReader reader) throws Exception {
        Console console = System.console();
        if (console != null) return console.readPassword("密码: ");
        System.out.print("密码: ");
        return reader.readLine().toCharArray();
    }

    static String detectAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface item = interfaces.nextElement();
                if (!item.isUp() || item.isLoopback()) continue;
                Enumeration<InetAddress> addresses = item.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address.getHostAddress().indexOf(':') < 0) return address.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception ignored) { return "local"; }
    }

    private static Path option(String[] args, String name, Path fallback) {
        for (int i = 0; i + 1 < args.length; i++) if (name.equals(args[i])) return Paths.get(args[i + 1]);
        return fallback;
    }

    private static boolean contains(String[] args, String value) {
        for (String item : args) if (value.equals(item)) return true;
        return false;
    }

    private static void usage() {
        System.out.println("LogMonitor Agent " + VERSION);
        System.out.println("  configure [--config PATH] [--allow-http]");
        System.out.println("  run [--config PATH] [--data PATH] [--once]");
        System.out.println("  status [--config PATH] [--data PATH]");
        System.out.println("  check [--config PATH] [--data PATH]");
    }
}
