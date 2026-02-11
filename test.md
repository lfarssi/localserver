
to test normal connection :
curl -i http://localhost:8080/
curl -i http://localhost:8081/notfound


Keep-Alive test
curl -i --http1.1 --keepalive-time 2 http://localhost:8080/

Upload test
curl -i -X POST --data-binary @www/index.html http://localhost:8080/upload


Delete test
curl -i -X DELETE http://localhost:8080/upload/<FILENAME_FROM_UPLOAD>


to test the upload :


curl -i -X POST --data-binary @www/index.html http://localhost:8080/upload


printf 'POST /upload HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nContent-Type: text/plain\r\n\r\nE\r\nHelloChunked!\n\r\n0\r\n\r\n' | nc -N localhost 8080



 Test CGI
GET
curl -i "http://localhost:8080/cgi/hello.py?name=fahd"

POST (unchunked)
curl -i -X POST "http://localhost:8080/cgi/hello.py?name=fahd" -d "hi=1"


POST (chunked) — now that chunked works ✅

printf 'POST /cgi/hello.py?name=fahd HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nContent-Type: application/x-www-form-urlencoded\r\n\r\n4\r\nhi=1\r\n0\r\n\r\n' | nc -N localhost 8080
