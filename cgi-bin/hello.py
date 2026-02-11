#!/usr/bin/env python3
import os
import sys

print("Content-Type: text/plain; charset=utf-8")
print("Status: 200 OK")
print()
print("Hello from CGI!")

for i in range(0,1000000):
    print("Salaam: "+str(i))


print("REQUEST_METHOD =", os.getenv("REQUEST_METHOD"))
print("QUERY_STRING =", os.getenv("QUERY_STRING"))
data = sys.stdin.read()
print("BODY =", data)
print("PATH_INFO =", os.getenv("PATH_INFO"))
print("EXTRA_PATH_INFO =", os.getenv("EXTRA_PATH_INFO"))