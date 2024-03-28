package searchengine;

import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import searchengine.services.LemmaService;
import searchengine.services.ServiceImpl.LemmaServiceImpl;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class Main {
    public static void main(String[] args) throws IOException {
        // Создание экземпляра сервиса лемматизации
        LemmaService lemmaService = new LemmaServiceImpl();

        // Пример текста для лемматизации
        String text = "Повторное появление леопарда в Осетии позволяет предположить, " +
                "что леопард постоянно обитает в некоторых районах Северного Кавказа.";

        // Получение лемм из текста
        Map<String, Integer> lemmas = lemmaService.getLemmasFromText(text);

        // Вывод результатов лемматизации
        System.out.println("Леммы и количество упоминаний в тексте:");
        for (Map.Entry<String, Integer> entry : lemmas.entrySet()) {
            System.out.println(entry.getKey() + " — " + entry.getValue());
        }

        // Пример слова для получения его леммы
        String word = "леопард";

        // Получение леммы для слова
        String lemma = lemmaService.getLemmaByWord(word);

        // Вывод леммы слова
        System.out.println("\nЛемма для слова \"" + word + "\": " + lemma);
    }
}
