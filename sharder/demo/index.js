var http = require('http');
var os = require('os');

var server = http.createServer((request, response) => {
  console.log(request.url)
  response.end(request.url + ' served by ' + os.hostname() + '\n');
});

server.listen(80, (err) => {
  if (err) {
    return console.log('error starting: ', err);
  }

  console.log('server is listening on 80'); 
});
