package sedona.sox;

/**
 * Tells the device to drop its file transfer. The stock client sends this only after a complete
 * transfer; when it gives up on a read (receive timeout) the device keeps the transfer open and
 * answers every later file request with "busy" until the session ends.
 */
public final class FileTransferClose {
  private FileTransferClose() {}

  public static void send(SoxClient client) throws Exception {
    Msg response = client.request(Msg.prepareRequest('z'));
    response.checkResponse('Z');
  }
}
