import java.io.BufferedReader;
import java.io.InputStreamReader;

import java.util.ArrayList;
import java.util.stream.*;
import java.util.regex.*;

import java.net.URL;

import java.sql.*;

import com.google.gson.JsonParser;

import org.apache.http.*;

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
    private static final JsonParser jsonparser = new JsonParser();

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
            .peek(System.out::println)
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
        System.out.println("Stored url: " + url);
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
            // already properly hosted, just pass it along
            return Stream.of(url);
        } else if (url.getHost().equals("imgur.com") && url.getPath().startsWith("/a/")) {
            // album case, fetch urls of all the images in the album and add them separately
            String albumToken = url.getPath().substring(3)); // discard the /a/
            HttpGet req = new HttpGet("https://api.imgur.com/3/gallery/album/" + albumToken);

            // add auth header
            req.addHeader("Authorization", "Client-ID " + CLIENT_ID);

            System.out.println("Resolving album: " + albumToken);
            HttpResponse res = http.execute(req);
            if(res.getStatusLine().getStatusCode() == 200){
                // get the response, extract the image array
                String json = EntityUtils.toString(res.getEntity());
                JsonObject root = jsonparser.parse(json).getAsJsonObject();
                JsonArray images = root.getAsJsonArray("images");

                // get all the image urls
                ArrayList<URL> urls = new ArrayList<>();
                for(int i = 0; i < images.size(); i++){
                    JsonObject image = images.get(i).getAsJsonObject();
                    String url = image.get("link").getAsString();
                    urls.add(new URL(url));
                }
                return urls.stream();
            } else {
                // something went wrong
                System.out.println("Non-200 statuscode from album info fetch: " + res.getStatusLine().toString());
                return Stream.empty();
            }
        } else if (isImageUrl(url)) {
            // image hosted elsewhere on the web, rehost to imgur
            HttpPost req = new HttpPost("https://api.imgur.com/3/image/");

            // add auth header
            req.addHeader("Authorization", "Client-ID " + CLIENT_ID);

            // add the image path to the request
            List<NameValuePair> nvps = new ArrayList<>();
            nvps.add(new BasicNameValuePair("image", url.toString()));
            req.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));

            System.out.println("Uploading image from: " + url.getHost());
            HttpResponse res = http.execute(req);
            if(res.getStatusLine().getStatusCode() == 200){
                // get the response, extract the image link
                String json = EntityUtils.toString(res.getEntity());
                JsonObject root = jsonparser.parse(json).getAsJsonObject();
                JsonObject image = root.get("data").getAsJsonObject();
                String url = image.get("link").getAsString();
                return Stream.of(new URL(url));
            } else {
                // something went wrong
                System.out.println("Non-200 statuscode from image upload: " + res.getStatusLine().toString());
                return Stream.empty();
            }
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
