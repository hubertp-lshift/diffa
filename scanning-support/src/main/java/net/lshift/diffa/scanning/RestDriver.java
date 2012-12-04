package net.lshift.diffa.scanning;

import com.ning.http.client.*;
import net.lshift.diffa.adapter.scanning.ScanAggregation;
import net.lshift.diffa.adapter.scanning.ScanConstraint;
import net.lshift.diffa.adapter.scanning.ScanResultEntry;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.file.DataFileStream;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.joda.time.DateTime;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

public class RestDriver implements Scannable {

  private AsyncHttpClient client = new AsyncHttpClient();
  private String url;
  private Realm realm;

  public RestDriver(String url, String user, String password) {
    this.url = url;
    this.realm = new Realm.RealmBuilder()
                          .setPrincipal(user)
                          .setPassword(password)
                          .setUsePreemptiveAuth(true)
                          .setScheme(Realm.AuthScheme.BASIC)
                          .build();
  }

  @Override
  public Set<ScanResultEntry> scan(Set<ScanConstraint> constraints, Set<ScanAggregation> aggregations, int maxSliceSize) {

    Response response = null;

    FluentStringsMap queryParams = ScanRequestEncoder.packRequest(constraints, aggregations, maxSliceSize);



    Request req =
      new RequestBuilder("GET").setUrl(url).
                                setQueryParameters(queryParams).
                                build();



    final Set<ScanResultEntry> entries = new HashSet<ScanResultEntry>();
    final DatumReader<net.lshift.diffa.scanning.ScanResultEntry> reader = new SpecificDatumReader<net.lshift.diffa.scanning.ScanResultEntry>(net.lshift.diffa.scanning.ScanResultEntry.class);

    AsyncHandler<Response> handler = new AsyncHandler<Response>() {

      private final Response.ResponseBuilder builder = new Response.ResponseBuilder();

      @Override
      public void onThrowable(Throwable t) {
        throw new RuntimeException(t);
      }

      @Override
      public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {

        Decoder decoder = DecoderFactory.get().binaryDecoder(bodyPart.getBodyPartBytes(), null);

        while (true) {
          try {

            net.lshift.diffa.scanning.ScanResultEntry entry = reader.read(null, decoder);
            ScanResultEntry e = new ScanResultEntry();

            if (entry.getId() != null) {
              e.setId(entry.getId().toString());
            }
            if (entry.getVersion() != null) {
              e.setVersion(entry.getVersion().toString());
            }
            if (entry.getLastUpdated() != null) {
              e.setLastUpdated(new DateTime(entry.getLastUpdated()));
            }
            if (entry.getAttributes() != null) {
              Map<String,String> tmp = new HashMap<String, String>();
              for (Map.Entry<CharSequence,CharSequence> me : entry.getAttributes().entrySet()) {
                tmp.put(me.getKey().toString(), me.getValue().toString());
              }
              e.setAttributes(tmp);
            }

            entries.add(e);

          } catch (EOFException e) {
            break;
          }
        }


        return STATE.CONTINUE;
      }

      @Override
      public STATE onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
        builder.accumulate(responseStatus);
        return STATE.CONTINUE;
      }

      @Override
      public STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception {
        builder.accumulate(headers);
        return STATE.CONTINUE;
      }

      @Override
      public Response onCompleted() throws Exception {
        return builder.build();
      }
    };

    try {
      client.prepareRequest(req).
             setRealm(realm).
             execute(handler).
             get();

    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    return entries;
  }
}
