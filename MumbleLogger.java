import java.io.BufferedReader;
import java.io.InputStreamReader;  
import java.util.ArrayList; 
import java.util.stream.*;
import java.util.regex.*;

import java.sql.*;

public class MumbleLogger {
    private static final Pattern p = Pattern.compile("<a href=\"(https?://[\\S]*)\"");

    private static Connection conn;
    
    public static void main(String[] args) {
        
        try {
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection("jdbc:sqlite:urls.db");

            PreparedStatement createTable = conn.prepareStatement("CREATE TABLE IF NOT EXISTS urls (time text, url text);");
            createTable.execute();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try{
                        System.out.println("losing connection."); // C provided by keyboard interupt ("^C")
                        conn.close();
                    } catch (SQLException e){
                        e.printStackTrace();
                    }    
                }));
        } catch (Exception e){
            e.printStackTrace();
            System.exit(-1);
        }


        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));        
        br.lines().filter(s -> s.startsWith("tdflog:")).flatMap(MumbleLogger::extractUrls).forEach(MumbleLogger::storeUrl);
                
    }

    private static Stream<String> extractUrls(String input){
        Matcher m = p.matcher(input);

        ArrayList<String> matches = new ArrayList<>();
        while(m.find()){
            matches.add(m.group(1));            
        }     
        return matches.stream();
    }

    private static void storeUrl(String url){
        System.out.println("This is a stored url: " + url);
        try {
            PreparedStatement insert = conn.prepareStatement("INSERT INTO urls VALUES (datetime('now'), ?);");
            insert.setString(1, url);
            insert.execute();
        } catch (SQLException e){
            e.printStackTrace();    
        }        
    } 
}