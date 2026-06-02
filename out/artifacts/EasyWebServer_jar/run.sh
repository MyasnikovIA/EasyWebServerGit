#java -jar EasyWebServer.jar

#sudo apt-get install authbind  # Ubuntu/Debian

# Разрешить порт 80 для вашего пользователя
#sudo touch /etc/authbind/byport/80
#sudo chmod 500 /etc/authbind/byport/80
#sudo chown bars:bars /etc/authbind/byport/80  # замените bars на вашего пользователя


authbind --deep java -jar EasyWebServer.jar