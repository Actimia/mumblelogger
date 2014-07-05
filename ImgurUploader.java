import java.util.concurrent.Executors;

public class ImgurUploader {

    private static final String CLIENT_ID = "b8cdd34d59c9b9f";
    private static final ThreadPool pool = Executors.newCachedThreadPool();

    public static void uploadImage(String url){
        pool.execute(ImgurUploader::upload(url));
    }

    private static void upload(String url);
}
