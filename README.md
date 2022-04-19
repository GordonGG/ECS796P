# ECS796P-coursework
This is the coursework of ECS796P Distribute System Option 2 which base in lab1 and lab2
Using Springboot 
## how to start?
sudo apt uodate 
sudo apt install default-jdk maven git
git clone https://github.com/GordonGG/ECS796P.git
mvn clean
mvn install
mvn java:@grpcServer
mvn java:@grpcClient
