## Welcome to the High Speed HTTP Daemon (hshttpd) project.

Yes, it is another web server like Apache and Ngnix. I decided to write a new one almost 20 years late...

### Why a new web server?
First, I was restricted by a download policy at work and it was easier to write a new web server than to overwrite the company policies to download a regular one. Second, I could not find a web server that stored all files in memory. Yes, they have some cache mechanism, but as memory nowadays is too cheap, I was expecting to find a web server entire based on memory. So, this is the main purpose of the hshttpd, it loads once every single file in a base directory (www) and puts them into a hash map (memory), using as key the HTTP url (GET /some-dir/some-page). Last, it was awesome, I am a coder by nature, so it was a good exercise.


I have based this code on the netty.io http file server implementation (https://netty.io/4.1/xref/io/netty/example/http/file/package-summary.html).

It is a IntelliJ project, using Gradle manager. The server was developed in Groovy, please feel free to rewrite it into whatever programming language you like the most.

From git clone command:

> git clone https://github.com/sylvioazevedo/hshttpd.git

Just gradle it:

> gradle shadowJar

And then you just invoke like a Jar program:

> java -jar build/libs/hshttpd-1.0-SNAPSHOT-all.jar 

This project is licensed as GPL v3.0

Once started, you can interact with the app through the console stdin with the following commands:

  * relaod - Reload the files in the directory [www] into memory;
  * stop - Stop server;
  * start - Restart the stopped server;
  * restart - Stop and then restart the server.
  
In case of doubts or any problem, please send me an e-mail: <sylvioazevedo@gmail.com>
