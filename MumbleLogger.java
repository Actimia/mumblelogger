import java.io.BufferedReader;
import java.io.InputStreamReader;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.*;
import java.util.regex.*;

import java.net.URL;
import java.net.MalformedURLException;

import java.nio.charset.Charset;

import java.sql.*;

import com.google.gson.*;

import org.apache.http.*;
import org.apache.http.client.*;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.client.methods.*;
import org.apache.http.client.entity.UrlEncodedFormEntity;

public class MumbleLogger {
    // a rudimentary url pattern
    private static final Pattern atags = Pattern.compile("<a href=\"(https?://[\\S]*)\"");
    private static final Pattern imgtags = Pattern.compile("<img src=\"(.*)\"/>");

    // the database connection
    private static Connection conn;

    // the imgur auth token
    private static final String CLIENT_ID = "b8cdd34d59c9b9f";

    // client to use for api requests
    private static final HttpClient http = HttpClientBuilder.create()
        .setConnectionManager(new PoolingHttpClientConnectionManager())
        .build();

    private static final JsonParser jsonparser = new JsonParser();

    public static void main(String[] args) {
        try {
            // setup database
            Class.forName("com.mysql.jdbc.Driver");
            conn = DriverManager.getConnection("jdbc:mysql://localhost/feedback?"
              + "user=mumblelogger&password=tdfpro");

            // setup table
            PreparedStatement createTable = conn.prepareStatement("CREATE TABLE IF NOT EXISTS urls (int id, time text, url text) PRIMARY KEY (id);");
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

        System.out.println("Listening...");
        // the big operation
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        br.lines()
            .filter(s -> s.startsWith("tdflog:"))   // only interested in our messages
            //.peek(System.out::println) // for debug
            .flatMap(MumbleLogger::extractUrls)     // transform to a stream of URLs
            .flatMap(MumbleLogger::toImgur)         // check for domain etc, and rehost as necessary
            .forEach(MumbleLogger::storeUrl);       // store the urls to the database

    }

    private static Stream<URL> extractUrls(String input) {
        // find all img tags with base64 encoded data
        Matcher imgs = imgtags.matcher(input);
        ArrayList<URL> matches = new ArrayList<>();
        while(imgs.find()){
            URL url = uploadBase64(imgs.group(1));
            if(url != null){
                matches.add(url);
            }
        }

        // find all links
        Matcher m = atags.matcher(input);

        while (m.find()) {
            try {
                matches.add(new URL(m.group(1))); // group 0 is the whole expr
            } catch (MalformedURLException e) {
                System.out.println(e);
            }
        }
        return matches.stream();
    }

    private static URL uploadBase64(String b64){
        try {
            b64 = b64.substring(b64.indexOf(",")); // strip initial data
            b64 = b64.replace("%2B", "+");
            b64 = b64.replace("%2F", "/");
            b64 = b64.replace(" ", "\n");
            System.out.println("Uploading from b64: " + b64.length() + " bytes");

            HttpPost req = new HttpPost("https://api.imgur.com/3/image");

            // add auth header
            req.addHeader("Authorization", "Client-ID " + CLIENT_ID);

            // add the image path to the request
            List<NameValuePair> nvps = new ArrayList<>();
            nvps.add(new BasicNameValuePair("image", b64));
            nvps.add(new BasicNameValuePair("type", "base64"));
            req.setEntity(new UrlEncodedFormEntity(nvps, Charset.forName("UTF-8")));

            HttpResponse res = http.execute(req);
            if(res.getStatusLine().getStatusCode() == 200){
                // get the response, extract the image link
                String json = EntityUtils.toString(res.getEntity());
                JsonObject root = jsonparser.parse(json).getAsJsonObject();
                JsonObject image = root.get("data").getAsJsonObject();
                String link = image.get("link").getAsString();
                return new URL(link);
            } else {
                // something went wrong
                System.out.print("Non-200 statuscode from b64 upload: " + res.getStatusLine().toString());
                return null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
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
            String albumToken = url.getPath().substring(3); // discard the /a/
            HttpGet req = new HttpGet("https://api.imgur.com/3/album/" + albumToken);

            // add auth header
            req.addHeader("Authorization", "Client-ID " + CLIENT_ID);
            try {
                System.out.println("Resolving album: " + albumToken);
                HttpResponse res = http.execute(req);
                if(res.getStatusLine().getStatusCode() == 200){
                    // get the response, extract the image array

                    String json = EntityUtils.toString(res.getEntity());
                    JsonObject root = jsonparser.parse(json).getAsJsonObject();
                    JsonObject data = root.get("data").getAsJsonObject();
                    JsonArray images = data.getAsJsonArray("images");

                    // get all the image urls
                    ArrayList<URL> urls = new ArrayList<>();
                    for(int i = 0; i < images.size(); i++){
                        JsonObject image = images.get(i).getAsJsonObject();
                        String link = image.get("link").getAsString();
                        urls.add(new URL(link));
                    }
                    return urls.stream();
                } else {
                    // something went wrong
                    System.out.print("Non-200 statuscode from album info fetch: " + res.getStatusLine().toString());
                    return Stream.empty();
                }
            } catch (Exception e) {
                e.printStackTrace();
                return Stream.empty();
            }


        } else if (isImageUrl(url)) {
            // image hosted elsewhere on the web, rehost to imgur
            try {
                HttpPost req = new HttpPost("https://api.imgur.com/3/image/");
                // add auth header
                req.addHeader("Authorization", "Client-ID " + CLIENT_ID);

                // add the image path to the request
                List<NameValuePair> nvps = new ArrayList<>();
                nvps.add(new BasicNameValuePair("image", url.toString()));
                nvps.add(new BasicNameValuePair("type", "URL"));
                nvps.add(new BasicNameValuePair("description", "Image from " + url.getHost()));
                req.setEntity(new UrlEncodedFormEntity(nvps, Charset.forName("UTF-8")));

                System.out.println("Uploading image from: " + url.getHost());
                HttpResponse res = http.execute(req);
                if(res.getStatusLine().getStatusCode() == 200){
                    // get the response, extract the image link
                    String json = EntityUtils.toString(res.getEntity());
                    JsonObject root = jsonparser.parse(json).getAsJsonObject();
                    JsonObject image = root.get("data").getAsJsonObject();
                    String link = image.get("link").getAsString();
                    return Stream.of(new URL(link));
                } else {
                    // something went wrong
                    System.out.println("Non-200 statuscode from image upload: " + res.getStatusLine().toString());
                    return Stream.empty();
                }
            } catch (Exception e) {
                e.printStackTrace();
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
