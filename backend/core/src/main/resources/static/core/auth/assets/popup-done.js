// Landing page of the re-authentication popup: tells the shell tab through a BroadcastChannel (not window.opener) and closes itself.
(function () {
  try {
    var channel = new BroadcastChannel('slg-os-auth');
    channel.postMessage({ type: 'signed-in' });
    channel.close();
  } catch (e) {
    // BroadcastChannel is unavailable: the visible button below still works, the shell polls the session instead.
  }
  var button = document.getElementById('close-window');
  if (button) {
    button.addEventListener('click', function () { window.close(); });
  }
  window.setTimeout(function () { window.close(); }, 150);
})();
