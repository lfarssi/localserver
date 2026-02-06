package utils;

public class PathUtil {
    public static String normallizeUrlPath(String path){
        if(path==null|| path.isEmpty())return "/";
        if(path.charAt(0)!= '/') path="/"+path;
        path=path.replace("/{2,}", "/");
        path=path.replace("/./", "/");

        while(path.contains("/../")){
            int idx=path.indexOf("/../");
            if(idx==0){path=path.substring(3);continue;}
            int prev=path.lastIndexOf('/',idx-1);
            if(prev<0)break;
            path=path.substring(0,prev)+path.substring(idx+3);
        }
        return path;
    }
}
