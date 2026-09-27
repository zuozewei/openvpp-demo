import com.openvpp.gateway.mqtt.MqttIngestService;
import com.openvpp.gateway.model.DeviceMessage;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 第 06 篇「异常报文样本」与「耗时观测」的可复跑探针（专栏《虚拟电厂系统开发实战》）。
 *
 * 用途：
 *  1. 起真实 MqttIngestService（被测网关，经公共 broker），逐条发布六类异常样本 + 对照样本；
 *  2. 记录各阶段计时点（订阅端/发布端建连、每条发送时刻、接收时刻）与报文字节数；
 *  3. 验证 1MB 大报文发布时的连接行为与订阅端存活。
 *
 * 运行（在仓库根目录，先 mvn -pl openvpp-gateway -am package 或保证 target/classes 存在）：
 *   CP="$(cat /tmp/ovpp-cp.txt)"  # 或按 README 生成 gateway 依赖 classpath
 *   javac -encoding UTF-8 -cp "$CP:openvpp-gateway/target/classes" -d /tmp/probe tools/probe/IngestAnomalyProbe.java
 *   java -cp "/tmp/probe:$CP:openvpp-gateway/target/classes:$(ls ~/.m2/repository/org/slf4j/slf4j-simple/2.0.0/slf4j-simple-2.0.0.jar)" IngestAnomalyProbe [broker]
 *
 * 观察窗口：每条样本间隔 300ms；对照消息后等待 3s；1MB 后等待 5s。全部计时用毫秒纪元值打印。
 */
public class IngestAnomalyProbe {

    static final String DEFAULT_BROKER = "tcp://broker-cn.emqx.io:1883";
    static final long T0 = System.currentTimeMillis();

    static class Rec {
        String tag; String topic; int bytes; long sentAt; long receivedAt = -1; String note = "";
    }

    static final List<Rec> recs = new ArrayList<>();
    static final CountDownLatch done = new CountDownLatch(1);

    public static void main(String[] args) throws Exception {
        String broker = args.length > 0 ? args[0] : DEFAULT_BROKER;
        log("PROBE broker=" + broker + " pid=" + ProcessHandle.current().pid());

        MqttIngestService service = new MqttIngestService(broker, "ovpp-probe-sub-06", m -> {
            synchronized (recs) {
                for (int i = recs.size() - 1; i >= 0; i--) {
                    Rec r = recs.get(i);
                    if (r.receivedAt < 0 && r.topic.equals("openvpp/dev-001/telemetry")) {
                        // 就近匹配最近一条未确认的 telemetry 样本（发布有序、间隔 300ms，足够区分）
                        r.receivedAt = System.currentTimeMillis();
                        log("RECV tag=" + r.tag + " seq=" + m.getSeq() + " ts=" + m.getTs()
                                + " latencyMs=" + (r.receivedAt - r.sentAt)
                                + " payload=" + shortStr(m.getPayload()));
                        return;
                    }
                }
            }
        });
        long t = System.currentTimeMillis();
        service.connect();
        log("PHASE subscriber-connect ms=" + (System.currentTimeMillis() - t));
        log("SUBSCRIBED pattern=openvpp/+/+");

        MqttClient pub = new MqttClient(broker, "ovpp-probe-pub-06", new MemoryPersistence());
        t = System.currentTimeMillis();
        pub.connect();
        log("PHASE publisher-connect ms=" + (System.currentTimeMillis() - t));

        send(pub, "S1-非法JSON", "openvpp/dev-001/telemetry", "not-a-json", null);
        send(pub, "S2-缺ts-seq", "openvpp/dev-001/telemetry", "{\"payload\":{\"power\":1}}", null);
        send(pub, "S3-两段主题", "openvpp/dev-001", "{\"payload\":{\"power\":1}}", null);
        send(pub, "S4-未知类型", "openvpp/dev-001/tele", "{\"payload\":{\"power\":1}}", null);
        send(pub, "S6a-首发seq7", "openvpp/dev-001/telemetry", "{\"ts\":1758000000000,\"seq\":7,\"payload\":{\"power\":5}}", null);
        send(pub, "S6b-同seq重复", "openvpp/dev-001/telemetry", "{\"ts\":1758000000000,\"seq\":7,\"payload\":{\"power\":5}}", null);
        send(pub, "S6c-旧seq3补发", "openvpp/dev-001/telemetry", "{\"ts\":1758000000001,\"seq\":3,\"payload\":{\"power\":6}}", null);
        send(pub, "CTRL1-存活对照", "openvpp/dev-001/telemetry", "{\"ts\":1758000000002,\"seq\":8,\"payload\":{\"power\":7}}", null);
        Thread.sleep(3000);

        StringBuilder sb = new StringBuilder("{\"ts\":1758000000003,\"seq\":9,\"payload\":{\"big\":\"");
        char[] chunk = new char[1024];
        Arrays.fill(chunk, 'x');
        while (sb.length() < 1024 * 1024 - 32) sb.append(chunk);
        sb.append("\"}}");
        String big = sb.toString();
        Rec r5 = rec("S5-约1MB大报文", "openvpp/dev-001/telemetry", big);
        try {
            pub.publish(r5.topic, new MqttMessage(big.getBytes(StandardCharsets.UTF_8)));
            r5.sentAt = System.currentTimeMillis();
            log("SEND tag=S5-约1MB大报文 bytes=" + r5.bytes + " ok");
        } catch (MqttException e) {
            r5.sentAt = System.currentTimeMillis();
            r5.note = "publish threw REASON_CODE=" + e.getReasonCode() + " msg=" + e.getMessage();
            log("SEND-FAIL tag=S5-约1MB大报文 bytes=" + r5.bytes + " reasonCode=" + e.getReasonCode()
                    + " class=" + e.getClass().getSimpleName()
                    + (e.getCause() != null ? " cause=" + e.getCause().getClass().getSimpleName() : ""));
        }
        Thread.sleep(5000);

        if (!pub.isConnected()) {
            log("PHASE publisher-reconnect after disconnect");
            pub.close(); pub = new MqttClient(broker, "ovpp-probe-pub-06b", new MemoryPersistence());
            pub.connect();
        }
        send(pub, "CTRL2-大报文后存活", "openvpp/dev-001/telemetry", "{\"ts\":1758000000004,\"seq\":10,\"payload\":{\"power\":8}}", null);
        Thread.sleep(4000);

        log("PHASE summary subscriber-alive=" + "see-CTRL2-receipt");
        synchronized (recs) {
            for (Rec r : recs) {
                log("REC tag=" + r.tag + " bytes=" + r.bytes + " sentAt=" + r.sentAt
                        + " receivedAt=" + r.receivedAt
                        + (r.receivedAt > 0 ? " latencyMs=" + (r.receivedAt - r.sentAt) : " notReceived")
                        + (r.note.isEmpty() ? "" : " note=" + r.note));
            }
        }
        log("PROBE done wallMs=" + (System.currentTimeMillis() - T0));
        pub.disconnect();
        System.exit(0);
    }

    static void send(MqttClient pub, String tag, String topic, String body, Void unused) throws Exception {
        Rec r = rec(tag, topic, body);
        pub.publish(topic, new MqttMessage(body.getBytes(StandardCharsets.UTF_8)));
        r.sentAt = System.currentTimeMillis();
        log("SEND tag=" + tag + " topic=" + topic + " bytes=" + r.bytes);
        Thread.sleep(300);
    }

    static Rec rec(String tag, String topic, String body) {
        Rec r = new Rec();
        r.tag = tag; r.topic = topic; r.bytes = body.getBytes(StandardCharsets.UTF_8).length;
        synchronized (recs) { recs.add(r); }
        return r;
    }

    static void log(String s) {
        System.out.printf("%d [+%dms] %s%n", System.currentTimeMillis(), System.currentTimeMillis() - T0, s);
    }

    static String shortStr(java.util.Map<String, Object> p) {
        if (p == null) return "null";
        StringBuilder s = new StringBuilder("{");
        for (java.util.Map.Entry<String, Object> e : p.entrySet()) {
            String v = String.valueOf(e.getValue());
            s.append(e.getKey()).append('=')
             .append(v.length() > 24 ? v.charAt(0) + "..len" + v.length() : v).append(' ');
        }
        return s.append('}').toString();
    }
}
