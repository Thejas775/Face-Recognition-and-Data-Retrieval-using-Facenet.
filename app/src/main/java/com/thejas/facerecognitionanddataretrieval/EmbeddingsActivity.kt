package com.thejas.facerecognitionanddataretrieval

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.thejas.facerecognitionanddataretrieval.databinding.ActivityEmbeddingsBinding
import org.json.JSONArray
import java.io.File

class EmbeddingsActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityEmbeddingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityEmbeddingsBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        loadEmbeddings()
    }

    private fun loadEmbeddings() {
        val file = File(filesDir, "embeddings.json")

        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, "No saved embeddings found", Toast.LENGTH_SHORT).show()
            return
        }

        val jsonArray = JSONArray(file.readText())
        val stringBuilder = StringBuilder()

        for (i in 0 until jsonArray.length()) {
            val jsonObject = jsonArray.getJSONObject(i)
            val name = jsonObject.getString("name")
            val embeddings = jsonObject.getJSONArray("embeddings")

            stringBuilder.append("Name: $name\nEmbeddings: $embeddings\n\n")
        }

        viewBinding.textView.text = stringBuilder.toString()
    }
}
