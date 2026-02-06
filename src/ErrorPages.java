import java.nio.file.Files;
import java.nio.file.Path;

public class ErrorPages {
    public static Response response(ConfigLoader.Config cfg, int  code){
        String reason=switch(code){
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 413 -> "Payload Too Large";
            default -> "Internal Server Error";
        };
        try {
            Path p = Path.of(cfg.errorPagesDir, code+".html");
            if (Files.exists(p)){
                Response r= new Response();
                r.status=code;
                r.reason=reason;
                r.body=Files.readAllBytes(p);
                r.headers.put("Content-Type","text/html; charset=utf-8");
                return r;
            }
        }catch(Exception ignored){}
        String html = "<html><head><title>" +code +" "+ reason +"</title></head>"+
        "<body><h1>"+code + " "+ reason+"</h1></body></html>";
        return Response.text(code, reason, "text/html",html);
    }
}