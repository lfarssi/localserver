import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;

public class Server {
    private final ConfigLoader.Config cfg;
    private final Router router;

    private Selector selector;
    private final Map<SocketChannel, ConnectionContext> contexts=new HashMap<>();
    //timouts (tune later)
    private static final long IDLE_TIMEOUT_MS=15_000;//connection idle
    private static final long HEADER_TIMEOUT_MS=10_000;//header not finished
    private static final long BODY_TIMEOUT_MS=20_000;//body not finished

    public Server(ConfigLoader.Config cfg, Router router){
        this.cfg=cfg;
        this.router=router;
    }
    public void run() throws IOException{
        selector=Selector.open();
        //Bind multiple ports
        for(int port:cfg.ports){
            ServerSocketChannel ssc =ServerSocketChannel.open();
            ssc.configureBlocking(false);
            ssc.bind(new InetSocketAddress(cfg.host, port));
            ssc.register(selector,SelectionKey.OP_ACCEPT);
            System.out.println("Listening on" +cfg.host+":"+port);

        }
        // event loop
        while(true){
            try{
                selector.select(250);
                Iterator<SelectionKey> it=selector.selectedKeys().iterator();
                while(it.hasNext()){
                    SelectionKey key=it.next();
                    it.remove();

                    if(!key.isValid())continue;

                    if(key.isAcceptable())onAccept(key);
                    if(key.isReadable())onRead(key);
                    if(key.isWritable())onWrite(key);
                }
                enforceTimouts();

            }catch(Exception e){
                System.err.println("Loop error:"+e.getMessage());
                e.printStackTrace();
            }
        }
    }
    private void onAccept(SelectionKey key)throws IOException{
        ServerSocketChannel ssc=(ServerSocketChannel) key.channel();
        SocketChannel ch =ssc.accept();
        if (ch==null)return ;
        ch.configureBlocking(false);
        ch.socket().setTcpNoDelay(true);

        ConnectionContext ctx=new ConnectionContext(ch);
        contexts.put(ch,ctx);

        ch.register(selector,SelectionKey.OP_READ);
    }

    private void onRead(SelectionKey key){
        SocketChannel ch =(SocketChannel) key.channel();
        ConnectionContext ctx=contexts.get(ch);
        if(ctx==null){closeQuietly(ch);return;}
        ctx.lastActivityMs=System.currentTimeMillis();
        try{
            int n =ch.read(ctx.readBuffer);
            if(n==-1){closeConnection(ch);return;}
            if(n==0)return;
            ctx.readBuffer.flip();

            while(true){
                HttpParser.ParseResult pr=ctx.parser.parse(ctx.readBuffer,cfg.clientBodyLimitBytes);
                if(pr.status==HttpParser.Status.NEED_MORE)break;
                if(pr.status==HttpParser.Status.ERROR){
                    Response res=ErrorPages.response(cfg,400);
                    ctx.enqueue(res.toByteBuffers());
                    key.interestOps(SelectionKey.OP_WRITE);
                    ctx.closeAfterWrite=true;
                    break;
                }
                HttpModels.Request req=pr.request;
                Response res;
                try {
                    res=router.handle(req);

                } catch(Exception e){
                    System.err.println("Handler error: "+e.getMessage());
                    e.printStackTrace();
                    res=ErrorPages.response(cfg, 500);
                }
                ctx.enqueue(res.toByteBuffers());
                boolean close ="close".equalsIgnoreCase(req.headers.getOrDefault("connection", ""));
                if(close || res.closeAfterWrite) ctx.closeAfterWrite=true;
                key.interestOps(SelectionKey.OP_WRITE);
                if(ctx.readBuffer.remaining()==0)break;
            }
            ctx.readBuffer.compact();
        }catch(IOException e){
            closeConnection(ch);
        }
    }

    private void onWrite(SelectionKey key){
        SocketChannel ch =(SocketChannel) key.channel();
        ConnectionContext ctx=contexts.get(ch);
        if(ctx==null){closeQuietly(ch); return;}

        ctx.lastActivityMs=System.currentTimeMillis();
        try{
            while(!ctx.writeQueue.isEmpty()){
                ByteBuffer buf =ctx.writeQueue.peek();
                ch.write(buf);
                if(buf.hasRemaining()) break;
                ctx.writeQueue.poll();
            }
            if(ctx.writeQueue.isEmpty()){
                if(ctx.closeAfterWrite){
                    closeConnection(ch);
                    return;
                }
                key.interestOps(SelectionKey.OP_READ);
            }
        }catch(IOException e){
            closeConnection(ch);
        }
    }
    private void enforceTimouts(){
        long now =System.currentTimeMillis();
        List<SocketChannel> toClose =new ArrayList<>();

        for(var entry:contexts.entrySet()){
            SocketChannel ch =entry.getKey();
            ConnectionContext ctx=entry.getValue();
            long idle=now-ctx.lastActivityMs;
            if(idle>IDLE_TIMEOUT_MS){
                toClose.add(ch);
                continue;
            }

            long stageAge=now -ctx.parser.stageStarMs;
            switch (ctx.parser.stage) {
                case HEADERS -> {
                    if(stageAge>HEADER_TIMEOUT_MS) toClose.add(ch);
                }
                    
            case BODY, CHUNKED ->{
                if(stageAge>BODY_TIMEOUT_MS)toClose.add(ch);
            }
                default->{}
            }
        }
        for(SocketChannel ch:toClose) closeConnection(ch);
    }
    private void closeConnection(SocketChannel ch){
        contexts.remove(ch);
        closeQuietly(ch);
    }
    private static void closeQuietly(Channel ch){
        try{
            ch.close();
        } catch(Exception ignored){

        }
    }
    static final class ConnectionContext{
        final SocketChannel ch;
        final ByteBuffer readBuffer=ByteBuffer.allocateDirect(64*1024);
        final Deque<ByteBuffer> writeQueue=new ArrayDeque<>();
        final HttpParser parser= new HttpParser();

        long lastActivityMs=System.currentTimeMillis();
        boolean closeAfterWrite=false;
        ConnectionContext(SocketChannel ch){this.ch=ch;}
        void enqueue(List<ByteBuffer> bufs){writeQueue.addAll(bufs);}
    }
}
