package str_exporter;

import basemod.BaseMod;
import basemod.ModPanel;
import basemod.interfaces.*;
import com.evacipated.cardcrawl.modthespire.Loader;
import com.evacipated.cardcrawl.modthespire.ModInfo;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;

@SpireInitializer
public class SlayTheRelicsExporter implements
        RelicGetSubscriber,
        StartGameSubscriber,
        PostCreateStartingRelicsSubscriber,
        PostInitializeSubscriber,
        PostUpdateSubscriber
{

    public static final Logger logger = LogManager.getLogger(SlayTheRelicsExporter.class.getName());
    public static final String MODID = "SlayTheRelicsExporter";

    private static String login = null;
    private static String secret = null;
    private static String version = "";

//    private static final String EBS_URL = "https://localhost:8080";
    private static final String EBS_URL = "https://slaytherelics.xyz:8081";
    private static final int MAX_RELICS = 25;

    private long lastBroadcast = System.currentTimeMillis();
    private String lastBroadcastJson = "";
    private static final long MIN_SAME_BROADCAST_PERIOD_MILLIS = 5 * 1000;
    private static final long MAX_BROADCAST_PERIOD_MILLIS = 20 * 1000;
    public static SlayTheRelicsExporter instance = null;

    public SlayTheRelicsExporter()
    {
        logger.info("Slay The Relics Exporter initialized!");
        BaseMod.subscribe(this);
    }

    private static boolean areCredentialsValid() {
        return login != null && secret != null;
    }

    public static String getVersion() {
        for (ModInfo info : Loader.MODINFOS) {
            if (info.ID.equals(MODID)) {
                return info.ModVersion.toString();
            }
        }

        return "unkwnown";
    }

    public static void initialize()
    {
        logger.info("initialize() called!");
        version = getVersion();
        instance = new SlayTheRelicsExporter();

        try {

            String data = new String(Files.readAllBytes(Paths.get("slaytherelics_config.txt")));

            String[] lines = data.split("\r\n");

            login = lines[0].split(":")[1];
            secret = lines[1].split(":")[1];

            logger.info("loaded login " + login + " and secret " + secret);
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (!areCredentialsValid()) {
            logger.info("slaytherelics_config.txt wasn't loaded, check if it exists.");
        }
    }

    public void relicPageChanged() {
        logger.info("Relic Page Changed");
        if (areCredentialsValid())
            check();
    }

    @Override
    public void receiveRelicGet(AbstractRelic abstractRelic) {
        logger.info("Relic Acquired");
        if (areCredentialsValid())
            check(abstractRelic);
    }

    @Override
    public void receiveStartGame() {
        logger.info("Start Game received");
        if (areCredentialsValid())
            check();
    }

    @Override
    public void receivePostCreateStartingRelics(AbstractPlayer.PlayerClass playerClass, ArrayList<String> arrayList) {
        if (areCredentialsValid())
            check();
    }

    private void check() {
        check(null);
    }

    private void check(AbstractRelic receivedRelic) {
        if (areCredentialsValid()) {
            broadcastRelics(receivedRelic);
        } else {
            logger.info("Either your secret or your login are null. The config file has probably not loaded properly");
        }
    }

    private static String sanitize(String str) {
        str = str.replace("\"", "\\\"");
        str = str.replace("[R]", "[E]");
        str = str.replace("[G]", "[E]");
        str = str.replace("[B]", "[E]");
        str = str.replace("[W]", "[E]");
        str = str.replace("NL", "<br>");

        return str;
    }

    private static String removeSecret(String str) {
        String pattern = "\"secret\": \"[a-z0-9]*\"";
        return str.replaceAll(pattern, "\"secret\": \"********************\"");
    }

    private void broadcastRelics(AbstractRelic receivedRelic) {
        logger.info("broadcasting relics");

        int pageId = AbstractRelic.relicPage; // send over relic page ID
        ArrayList<AbstractRelic> relics = new ArrayList<>(); // send over relics

        String character = "";

        if (CardCrawlGame.isInARun() && CardCrawlGame.dungeon != null && CardCrawlGame.dungeon.player != null) {
            relics = (ArrayList<AbstractRelic>) CardCrawlGame.dungeon.player.relics.clone();
            if (receivedRelic != null) {
                relics.add(receivedRelic);
            }
            character = CardCrawlGame.dungeon.player.getClass().getSimpleName();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"msg_type\": \"set_relics\", ");
        sb.append("\"streamer\": {\"login\": \"" + login + "\", \"secret\": \"" + secret + "\"}, ");
        sb.append("\"meta\": {\"version\": \"" + version + "\"}, ");
        sb.append("\"message\": {");
        sb.append("\"relics\": [");

        int first_index = pageId * MAX_RELICS;
        int last_index = Math.min((pageId + 1) * MAX_RELICS, relics.size());

        for (int i = first_index; i < last_index; i++) {
            AbstractRelic relic = relics.get(i);
            String header = relic.tips.get(0).header;
            String body = relic.tips.get(0).body;
            sb.append("{\"name\": \"" + sanitize(header) + "\", \"description\": \"" + sanitize(body) + "\"}");

            if (i < last_index - 1)
                sb.append(", ");
        }
        sb.append("], ");
        sb.append("\"is_relics_multipage\": \"" + (relics.size() > MAX_RELICS) + "\", ");
        sb.append("\"character\": \"" + character + "\"");
        sb.append("}}");

        logger.info(removeSecret(sb.toString()));
        broadcastJson(sb.toString());
    }


    private void broadcastJson(String json) {

        // prevent rapid repeated broadcasts
        if (System.currentTimeMillis() - lastBroadcast < MIN_SAME_BROADCAST_PERIOD_MILLIS && json.equals(lastBroadcastJson)) {
            logger.info("Aborting rapidly repeated broadcast");
            return;
        }

        lastBroadcast = System.currentTimeMillis();
        lastBroadcastJson = json;

        (new Thread(() -> {
            try {

                URL url = new URL(EBS_URL);
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("POST");
                con.setRequestProperty("Content-Type", "application/json");  //; utf-8
                con.setRequestProperty("Accept", "application/json");
                con.setDoOutput(true);

                OutputStream os = con.getOutputStream();
                byte[] input = json.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);

                BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
                logger.info("response: " + response.toString());

            } catch (Exception e) {
                e.printStackTrace();
            }
        })).start();
    }

    @Override
    public void receivePostInitialize() {
        ModPanel settingsPanel = new ModPanel();

        BaseMod.registerModBadge(ImageMaster.loadImage(
                "SlayTheRelicsExporterResources/img/ink_bottle.png"),
                "Slay the Relics Exporter",
                "LordAddy",
                "This mod exports data to Slay the Relics Twitch extension. See the extension config on Twitch for setup instructions.",
                settingsPanel);
    }

    @Override
    public void receivePostUpdate() {
        if (System.currentTimeMillis() - lastBroadcast > MAX_BROADCAST_PERIOD_MILLIS) {
            if (areCredentialsValid())
                check();
        }
    }
}