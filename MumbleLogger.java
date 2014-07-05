import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.stream.*;
import java.net.URL;
import java.util.regex.*;

import java.sql.*;

public class MumbleLogger {
    // a rudimentary url pattern
    private static final Pattern p = Pattern.compile("<a href=\"(https?://[\\S]*)\"");

    // the database connection
    private static Connection conn;

    // the imgur auth token
    private static final String CLIENT_ID = "b8cdd34d59c9b9f";

    // client to use for api requests
    private static final HttpClient http = new HttpClientBuilder()
        .setConnectionManager(new MultiThreadedHttpConnectionManager())
        .build();

    public static void main(String[] args) {
        try {
            // setup database
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection("jdbc:sqlite:urls.db");

            // setup table
            PreparedStatement createTable = conn.prepareStatement("CREATE TABLE IF NOT EXISTS urls (time text, url text);");
            createTable.execute();

            // add proper shutdown behavior
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try{
                        System.out.println("losing connection."); // C provided by keyboard interupt ("^C")
                        conn.close();
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                }));
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }

        // the big operation
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        br.lines().parallel()                       // parallel since we will be doing net ops
            .filter(s -> s.startsWith("tdflog:"))   // only interested in our messages
            .flatMap(MumbleLogger::extractUrls)     // transform to a stream of URLs
            .flatMap(MumbleLogger::toImgur)         // check for domain etc, and rehost as necessary
            .forEach(MumbleLogger::storeUrl);       // store the urls to the database

    }

    private static Stream<URL> extractUrls(String input) {
        Matcher m = p.matcher(input);

        ArrayList<URL> matches = new ArrayList<>();
        while (m.find()) {
            try {
                matches.add(new URL(m.group(1))); // group 0 is the whole expr
            } catch (MalformedURLException e) {
                System.out.println(e);
            }
        }
        return matches.stream();
    }

    private static void storeUrl(URL url) {
        System.out.println("This is a stored url: " + url);
        try {
            PreparedStatement insert = conn.prepareStatement("INSERT INTO urls VALUES (datetime('now'), ?);");
            insert.setString(1, url.toString());
            insert.execute();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static Stream<URL> toImgur(URL url) {
        if(url.getHost().equals("i.imgur.com")) {
            return Stream.of(url);
        } else if (url.getHost().equals("imgur.com") && url.getPath().startsWith("/a/")) {
            // album case, fetch urls of all the images in the album and add them separately
            String albumToken = url.getPath().substring(3)); // discard the /a/
            HttpGet req = new HttpGet("https://api.imgur.com/3/gallery/album/" + albumToken);
            req.addHeader("Authorization", "Client-ID " + CLIENT_ID);
            HttpResponse res = http.execute(req);
            if(res.getStatusLine().getStatusCode() == 200){
                String json = EntityUtils.toString(res.getEntity());
            }
        } else if (isImageUrl(url)) {
            // image hosted elsewhere on the web, rehost to imgur

        } else {
            // not recognised as an image
            return Stream.empty();
        }
    }

    private static boolean isImageUrl(URL url) {
        return url.getPath().endsWith(".jpg")
            || url.getPath().endsWith(".png")
            || url.getPath().endsWith(".gif");
    }
}
