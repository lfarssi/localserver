javac -d out $(find src -name "*.java")
java -cp out Main config.json