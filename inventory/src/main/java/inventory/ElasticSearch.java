package inventory;

import inventory.config.ElasticsearchConfig;
import inventory.models.Inventory;
import inventory.models.InventoryRepo;
import okhttp3.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.PrintWriter;
import java.io.StringWriter;

@Component("ElasticSearch")
public class ElasticSearch {

    @Autowired
    @Qualifier("inventoryRepo")
    private InventoryRepo inventoryRepo;

    @Autowired
    ElasticsearchConfig config;
    private String url;
    private String user;
    private String password;
    private String index;
    private String doc_type;
    private OkHttpClient client;

    @PostConstruct
    public void init() {
        // Get es_url, es_index, and es_doc_type
        url = config.getUrl();
        user = config.getUser();
        password = config.getPassword();

        // Optional
        index = config.getIndex();
        if (index == null || index.equals("")) {
            index = "micro";
        }

        doc_type = config.getDoc_type();
        if (doc_type == null || doc_type.equals("")) {
            doc_type = "items";
        }

        client = new OkHttpClient();

        // Load cache
        System.out.println("***********\nInitializing cache:");
        load_cache();
        System.out.println("Cache initialized!\n***********");
    }

    public void load_cache () {
        // Fetch all the rows
        JSONArray rows = get_all_rows();

        // Then update cache
        load_rows_into_cache(rows);
    }

    // Subscribe to topic and start polling
    public void refresh_cache(JSONObject row) {
        long id = row.getLong("itemId");
        // Update row in database
        if (!inventoryRepo.exists(id)) {
            System.out.println("Item does not exist: " + id);
            return;
        }

        Inventory item = inventoryRepo.findOne(id);
        int new_stock = item.getStock() - row.getInt("count");
        System.out.println("Current stock: " + item.getStock());
        System.out.println("New Stock: " + new_stock);

        // Do the saving
        item.setStock(new_stock);
        inventoryRepo.save(item);

        load_cache();
    }

    // Get all rows from database
    public JSONArray get_all_rows() {
        JSONArray rows = null;
        try {
            Iterable<Inventory> items = inventoryRepo.findAll();
            StringBuilder rows_string = new StringBuilder();

            // Build the string for JSONArray
            System.out.println("Rows:");
            rows_string.append("[");
            for (Inventory item : items) {
                rows_string.append(item.toString());
                rows_string.append(",");
            }
            // Remove last ,
            rows_string.deleteCharAt(rows_string.length() - 1);

            // Finish array
            rows_string.append("]");
            System.out.println("rows_string: " + rows_string.toString());

            rows = new JSONArray(rows_string.toString());

        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            System.out.println(sw.toString());

            rows = new JSONArray("[]");
        }

        return rows;
    }

    // Subscribe to topic and start polling
    public void load_rows_into_cache(JSONArray rows) {
        for (int i = 0; i < rows.length(); i++) {
            JSONObject jsonObj = rows.getJSONObject(i);
            System.out.println("Loading row: \n" + jsonObj.toString());

            try {
                MediaType mediaType = MediaType.parse("application/json");
                RequestBody body = RequestBody.create(mediaType, jsonObj.toString());

                // Build URL
                String url = String.format("%s/%s/%s/%s", this.url, index, doc_type, jsonObj.getInt("id"));

                Request.Builder builder = new Request.Builder().url(url)
                        .put(body)
                        .addHeader("content-type", "application/json");

                if (user != null && !user.equals("") && password != null && !password.equals("")) {
                    System.out.println("Adding credentials to request");
                    builder.addHeader("Authorization", Credentials.basic(user, password));
                }

                Request request = builder.build();

                Response response = client.newCall(request).execute();
                String resp_string = response.body().string();
                System.out.println("resp_string: \n" + resp_string);
                JSONObject resp = new JSONObject(resp_string);
                boolean created = resp.getBoolean("created");

                System.out.printf("Item %s was %s\n\n", resp.getString("_id"), ((created == true) ? "Created" : "Updated"));

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}