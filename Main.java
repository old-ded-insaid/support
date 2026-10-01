import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Оконная оболочка для разбора логов касс.
 * Только стандартная библиотека Java (Swing + nio + regex + time).
 *
 * <p>Как пользоваться:
 * 1. "Выбрать файл" — прикрепить .txt (если не выбрать, ищутся все .txt в src).
 * 2. Ввести ВРЕМЯ (можно пустое) ИЛИ СУММУ чека (36 / 1165,96).
 *    - время приоритетнее суммы;
 *    - время может быть пустым — тогда ищем только по сумме;
 *    - если по сумме нашлось несколько чеков — отчет строится по КАЖДОМУ чеку
 *      по очереди: сначала весь первый чек, потом второй и т.д.
 * 3. Окно = ±N минут (по умолчанию 10+10=20 минут).
 */
public class Main {

    // ---------- Регулярки ----------
    private static final Pattern LOG_PATTERN = Pattern.compile(
            "^\\[?(\\d{2}\\.\\d{2}\\.\\d{4}\\s+\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?)\\]?\\s*\\t?\\s*(.*)$");

    private static final Pattern ERROR_WORD =
            Pattern.compile("ошибка", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern CHECK_FAIL_PATTERN = Pattern.compile(
            "чек не проведен|отмена чека|некорректный код маркировки",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Шумовые сообщения, которые в отчет не выводим. */
    private static final Pattern USER_MSG_EXCLUDE_PATTERN = Pattern.compile(
            "печатать кассовый чек на ККМ",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern PARTIA_PATTERN = Pattern.compile("DSCheckNaklDataID\\s*=\\s*(\\d+)");
    private static final Pattern PARTIA_ALT_PATTERN =
            Pattern.compile("партия\\s*№\\s*(\\d+)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern QUANTITY_PATTERN =
            Pattern.compile("quantity\\s*=\\s*(\\d+[.,]?\\d*)", Pattern.CASE_INSENSITIVE);

    private static final Pattern MARK_STRICT_PATTERN = Pattern.compile("str_input\\s*=\\s*,Маркировка:\\s*(.*)");
    private static final Pattern MARK_ANY_PATTERN = Pattern.compile("str_input\\s*=\\s*(?:,Маркировка:\\s*)?(\\S+)");
    private static final Pattern MARK_WORD_PATTERN =
            Pattern.compile("Маркировка:\\s*(\\S+)", Pattern.UNICODE_CASE);
    private static final Pattern MARK_CHECK_PATTERN =
            Pattern.compile("Проверяем марку:\\s*(\\S+)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern CHECK_NUM_PATTERN =
            Pattern.compile("чек\\s*[№#]\\s*(\\d+)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // Тип продажи: Нажата кнопка "Продажа [F8]" / "Продажа по карте [F3]"
    private static final Pattern SALE_BUTTON_PATTERN = Pattern.compile(
            "Нажата кнопка\\s+\"?([^\"\n]*?Продажа[^\"\n]*?)\"?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // Сообщение пользователю
    private static final Pattern USER_MSG_PATTERN = Pattern.compile(
            "Сообщение пользователю:\\s*\"?(.+?)\"?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // Скидка / дисконт / бонус
    private static final Pattern DISCOUNT_KEYWORD_PATTERN = Pattern.compile(
            "скидк|дисконт|бонус",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Синхронизация скидок — это НЕ применение скидки, в отчет не пишем. */
    private static final Pattern SYNC_PATTERN = Pattern.compile(
            "синхронизац",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Технические строки модулей — только товары и чек, это в отчет не пишем. */
    private static final Pattern TECH_PATTERN = Pattern.compile(
            "KM81|ExecSetDiscount|\\.uPiot|ApiThread|HTTP_SendPost|\\.EXE used memory|MyCalcFields|UPDATE OR INSERT",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DISCOUNT_APPLY_PATTERN = Pattern.compile(
            "Попытка применить дисконтную карту:\\s*(\\S+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern DISCOUNT_CARD_GENERIC_PATTERN = Pattern.compile(
            "дисконтн\\w*\\s+карт\\w*[^\\d+]*(\\+?\\d[\\d\\s\\-]{4,})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern PHONE_GENERIC_PATTERN = Pattern.compile(
            "(?:номер\\s+телефона|телефон)[^\\d+]*(\\+?\\d[\\d\\s\\-()]{5,})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern DISCOUNT_SUM_PATTERN = Pattern.compile(
            "Сумма скидки:\\s*([0-9\\s.,]+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Запуск (перезапуск) программы = признак того, что она была вылетевшей.
     * Регистр не важен, пробелы между словами — тоже (в логах бывает по-разному).
     * Покрывает варианты: «Запуск программы», «запуск программы», «ЗАПУСК ПРОГРАММЫ»,
     * а также «Программа запущена», «Program started».
     */
    private static final Pattern PROGRAM_START_PATTERN = Pattern.compile(
            "запуск\\s+программы|программа\\s+запущена|program\\s+started|start\\s+program",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // ---------- Форматы даты ----------
    private static final DateTimeFormatter LOG_DATE_TIME = new DateTimeFormatterBuilder()
            .appendPattern("dd.MM.yyyy HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.MILLI_OF_SECOND, 1, 3, true)
            .optionalEnd()
            .toFormatter(Locale.ROOT);

    private static final DateTimeFormatter TARGET_WITH_MS =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS", Locale.ROOT);
    private static final DateTimeFormatter TARGET_NO_MS =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.ROOT);

    /** Строки одного чека считаются одним чеком, если совпадения по сумме ближе этого интервала. */
    private static final Duration SAME_CHECK_GAP = Duration.ofMinutes(3);
    /** Ограничение, чтобы поиск частой суммы (например "36") не завесил окно. */
    private static final int MAX_CHECKS_TO_SHOW = 20;

    // ---------- GUI ----------
    private JFrame frame;
    private JLabel fileLabel;
    private Path chosenFile;
    private JTextField timeField;
    private JTextField sumField;
    private JTextField wordField;
    private JSpinner windowSpinner;
    private JSpinner fontSpinner;
    private JTextArea outputArea;
    private JButton findButton;
    private JScrollPane scrollArea;                 // для подсветки при перетаскивании
    private final Border scrollAreaBorder = BorderFactory.createEmptyBorder();
    private static final int MIN_FONT = 8;
    private static final int MAX_FONT = 32;
    /** Сколько строк прокручивает одно «щёлчок» колеса мыши. */
    private static final int WHEEL_LINES = 9;
    private int fontSize = 14;

    public static void main(String[] args) {
        // Системный вид Windows, чтобы отдельное приложение выглядело нативно
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // остаемся на стандартном
        }
        SwingUtilities.invokeLater(() -> new Main().createAndShow());
    }

    private void createAndShow() {
        frame = new JFrame("Парсер логов чеков");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setSize(950, 700);
        frame.setLayout(new BorderLayout(8, 8));
        // На весь экран при запуске
        frame.setExtendedState(JFrame.MAXIMIZED_BOTH);

        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        top.add(new JLabel("Файл:"), c);
        fileLabel = new JLabel("не выбран (будут искаться все .txt в src)");
        c.gridx = 1; c.weightx = 1;
        top.add(fileLabel, c);
        JButton browseBtn = new JButton("Выбрать файл...");
        c.gridx = 2; c.weightx = 0;
        top.add(browseBtn, c);
        browseBtn.setToolTipText("Можно просто перетащить .txt файл из папки в окно программы");
        browseBtn.addActionListener(e -> chooseFile());

        // 1) Поле времени МОЖЕТ быть пустым — стартуем с пустого
        c.gridx = 0; c.gridy = 1; c.weightx = 0;
        top.add(new JLabel("Время (можно пустое):"), c);
        timeField = new JTextField("", 25);
        timeField.setToolTipText("<html>Можно оставить пустым.<br>Форматы:<br>"
                + "дд.ММ.гггг ЧЧ:мм:сс.мсс — 11.09.2026 09:49:55.757<br>"
                + "дд.ММ.гггг ЧЧ:мм — 11.09.2026 09:49<br>"
                + "ЧЧ:мм или ЧЧ:мм:сс — 09:49 (возьмется ПЕРВАЯ запись на это время)</html>");
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1;
        top.add(timeField, c);
        c.gridwidth = 1;

        c.gridx = 0; c.gridy = 2;
        top.add(new JLabel("Сумма чека:"), c);
        sumField = new JTextField("", 25);
        sumField.setToolTipText("Например: 36 или 1165,96. Если время пустое — ищем по сумме.");
        c.gridx = 1; c.gridwidth = 2;
        top.add(sumField, c);
        c.gridwidth = 1;

        // Новое: поиск по слову (фрагменту текста лога)
        c.gridx = 0; c.gridy = 3; c.weightx = 0;
        top.add(new JLabel("Слово:"), c);
        wordField = new JTextField("", 25);
        wordField.setToolTipText("Ищет строки, содержащие это слово или фразу (без учета регистра).\n"
                + "Пусто — поиск по слову не выполняется.");
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1;
        top.add(wordField, c);
        c.gridwidth = 1;

        c.gridx = 0; c.gridy = 4;
        top.add(new JLabel("Окно ± мин:"), c);
        windowSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 120, 1));
        windowSpinner.setToolTipText("10 = 10 минут до и 10 после (итого 20 минут)");
        c.gridx = 1; c.weightx = 0;
        top.add(windowSpinner, c);
        findButton = new JButton("Найти");
        c.gridx = 2;
        top.add(findButton, c);
        findButton.addActionListener(e -> onFind());

        // Строка 5: размер шрифта (кнопки + спиннер + подсказка про Ctrl +/-)
        c.gridx = 0; c.gridy = 5; c.weightx = 0;
        top.add(new JLabel("Шрифт:"), c);
        JPanel fontPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton fontMinus = new JButton("A-");
        fontMinus.setToolTipText("Уменьшить шрифт (Ctrl+-)");
        JButton fontPlus = new JButton("A+");
        fontPlus.setToolTipText("Увеличить шрифт (Ctrl++)");
        fontSpinner = new JSpinner(new SpinnerNumberModel(fontSize, MIN_FONT, MAX_FONT, 1));
        fontSpinner.setToolTipText("Размер шрифта 8–32. Работает Ctrl+колесо мыши и Ctrl +/-");
        fontPanel.add(fontMinus);
        fontPanel.add(fontSpinner);
        fontPanel.add(fontPlus);
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1;
        top.add(fontPanel, c);
        c.gridwidth = 1;
        fontMinus.addActionListener(e -> setFontSize(fontSize - 1));
        fontPlus.addActionListener(e -> setFontSize(fontSize + 1));
        fontSpinner.addChangeListener(e -> setFontSize((Integer) fontSpinner.getValue()));

        frame.add(top, BorderLayout.NORTH);

        outputArea = new JTextArea();
        outputArea.setEditable(false);
        applyFontSize();
        outputArea.setText("Введите время ИЛИ сумму чека и нажмите «Найти».\nВремя может быть пустым — тогда поиск только по сумме.\n");
        final JScrollPane scroll = new JScrollPane(outputArea);
        // Автопрокрутка в начало после вывода результата
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        frame.add(scroll, BorderLayout.CENTER);
        scrollArea = scroll;

        // Ctrl +/- и Ctrl+колесо для шрифта
        outputArea.getInputMap().put(KeyStroke.getKeyStroke("control PLUS"), "fontPlus");
        outputArea.getInputMap().put(KeyStroke.getKeyStroke("control ADD"), "fontPlus");
        outputArea.getInputMap().put(KeyStroke.getKeyStroke("control EQUALS"), "fontPlus");
        outputArea.getInputMap().put(KeyStroke.getKeyStroke("control MINUS"), "fontMinus");
        outputArea.getInputMap().put(KeyStroke.getKeyStroke("control SUBTRACT"), "fontMinus");
        outputArea.getActionMap().put("fontPlus", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { setFontSize(fontSize + 1); }
        });
        outputArea.getActionMap().put("fontMinus", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { setFontSize(fontSize - 1); }
        });

        // Колесо мыши вешаем на сам JScrollPane и крутим полосы вручную.
        // Так колесо работает одинаково на любом ПК: свой обработчик на JTextArea
        // ломает штатную прокрутку Swing (полоса перестаёт двигаться).
        // Шаг — ровно WHEEL_LINES строк, чтобы не перелистывать пол-экрана.
        scroll.addMouseWheelListener(e -> {
            JScrollBar vBar = scroll.getVerticalScrollBar();
            JScrollBar hBar = scroll.getHorizontalScrollBar();
            if (e.isControlDown()) {
                setFontSize(fontSize + (e.getWheelRotation() < 0 ? 1 : -1));
                e.consume();
                return;
            }
            int units = e.getUnitsToScroll();
            if (units == 0) {
                units = e.getWheelRotation() < 0 ? -1 : 1;
            }
            int lineHeight = Math.max(1, outputArea.getFontMetrics(outputArea.getFont()).getHeight());
            int vStep = lineHeight * WHEEL_LINES * units;
            int hStep = lineHeight * 4 * units;
            if (e.isShiftDown()) {
                hBar.setValue(hBar.getValue() + hStep);
            } else {
                vBar.setValue(vBar.getValue() + vStep);
            }
            e.consume();
        });

        // Enter в полях ввода запускает поиск
        Action doSearch = new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { onFind(); }
        };
        for (JTextField f : new JTextField[]{timeField, sumField, wordField}) {
            f.addActionListener(doSearch);
        }

        JLabel hint = new JLabel("Enter — начать поиск. Колесо — прокрутка на 9 строк, "
                + "Ctrl+колесо — размер шрифта. Можно перетащить .txt файл из папки прямо в окно.");
        hint.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        frame.add(hint, BorderLayout.SOUTH);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // Перетаскивание файла: ставим обработчик на ВСЕ компоненты окна.
        // Иначе JTextArea / JTextField / JSpinner перехватывают дроп своим
        // TransferHandler (для copy/paste) и показывают запрещенный значок.
        SwingUtilities.invokeLater(() -> enableFileDropRecursively(frame.getContentPane()));
    }

    /** Рекурсивно вешает наш обработчик перетаскивания на все JComponent окна. */
    private void enableFileDropRecursively(Component component) {
        if (component instanceof JComponent) {
            ((JComponent) component).setTransferHandler(createDropHandler());
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                enableFileDropRecursively(child);
            }
        }
    }

    /** Drag & Drop: принимаем перетащенный файл в любое место окна. */
    private TransferHandler createDropHandler() {
        return new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                if (!support.isDrop()) {
                    return false;
                }
                boolean ok;
                try {
                    ok = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
                } catch (Exception e) {
                    ok = false;
                }
                // Подсветка рамкой, пока файл «висит» над окном
                if (ok) {
                    highlightDropArea(true);
                }
                return ok;
            }

            @Override
            public void exportDone(JComponent c, Transferable t, int action) {
                resetDropHighlight();
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    List<File> files = (List<File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    if (files == null || files.isEmpty()) {
                        return false;
                    }
                    // Ищем первый файл с расширением txt
                    Path picked = null;
                    for (File f : files) {
                        if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".txt")) {
                            picked = f.toPath();
                            break;
                        }
                    }
                    // если .txt среди перетащенных нет — берем первый файл как есть
                    if (picked == null && files.get(0).isFile()) {
                        picked = files.get(0).toPath();
                    }
                    if (picked == null) {
                        JOptionPane.showMessageDialog(frame,
                                "Нужно перетащить файл лога (.txt)",
                                "Файл не принят", JOptionPane.WARNING_MESSAGE);
                        return false;
                    }
                    final Path accepted = picked;
                    SwingUtilities.invokeLater(() -> {
                        setChosenFile(accepted);
                        outputArea.setText("Файл принят: " + accepted + "\n"
                                + "Введите время, сумму или слово и нажмите «Найти».\n");
                        outputArea.setCaretPosition(0);
                    });
                    return true;
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(frame,
                            "Не удалось прочитать файл: " + ex.getMessage(),
                            "Ошибка", JOptionPane.ERROR_MESSAGE);
                    return false;
                }
            }
        };
    }

    /** Подсветка окна при перетаскивании файла / сброс подсветки. */
    private void highlightDropArea(boolean on) {
        if (scrollArea == null) {
            return;
        }
        Runnable r = () -> {
            scrollArea.setBorder(on
                    ? BorderFactory.createLineBorder(new Color(0, 120, 215), 3)
                    : scrollAreaBorder);
            scrollArea.repaint();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    private void resetDropHighlight() {
        highlightDropArea(false);
    }

    /** Применяет выбранный файл и обновляет подпись. */
    private void setChosenFile(Path path) {
        chosenFile = path;
        fileLabel.setText(path.toString());
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser(Paths.get("src").toFile());
        chooser.setFileFilter(new FileNameExtensionFilter("Текстовые логи (*.txt)", "txt"));
        chooser.setMultiSelectionEnabled(false);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            setChosenFile(chooser.getSelectedFile().toPath());
        }
    }

    /** Установка размера шрифта с границами 8–32, применяется к полю вывода. */
    private void setFontSize(int size) {
        fontSize = Math.max(MIN_FONT, Math.min(MAX_FONT, size));
        if (fontSpinner != null && ((Integer) fontSpinner.getValue()) != fontSize) {
            fontSpinner.setValue(fontSize);
        }
        applyFontSize();
    }

    private void applyFontSize() {
        if (outputArea != null) {
            outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, fontSize));
        }
    }

    private void onFind() {
        String timeText = timeField.getText().trim();
        String sumText = sumField.getText().trim().replace('\u00A0', ' ');
        String wordText = wordField.getText().trim();
        int window = (Integer) windowSpinner.getValue();

        // 1) Время может быть пустым — требуем хотя бы что-то одно
        if (timeText.isEmpty() && sumText.isEmpty() && wordText.isEmpty()) {
            showError("Введите время, сумму чека ИЛИ слово для поиска.");
            return;
        }

        findButton.setEnabled(false);
        outputArea.setText("Поиск...\n");

        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() {
                return analyze(timeText, sumText, wordText, window);
            }
            @Override protected void done() {
                try { outputArea.setText(get()); outputArea.setCaretPosition(0); }
                catch (Exception ex) { showError("Ошибка разбора: " + ex.getMessage()); }
                finally { findButton.setEnabled(true); }
            }
        }.execute();
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(frame, msg, "Ошибка ввода", JOptionPane.WARNING_MESSAGE);
    }

    // ================= ЛОГИКА =================

    /**
     * Точка входа. Приоритет: время -> сумма -> слово.
     * - время задано → один отчет по окну времени;
     * - время пустое, есть сумма → ищем ВСЕ совпадения по сумме, отчет по каждому чеку;
     * - время и сумма пустые, есть слово → ищем все строки с этим словом.
     */
    private String analyze(String timeText, String sumText, String wordText, int window) {
        List<Path> files = filesToScan();
        if (files.isEmpty()) {
            return "Файлы .txt не найдены. Выберите файл или положите логи в папку src.";
        }

        // Время задано — работаем как раньше, сумма/слово игнорируются
        if (!timeText.isEmpty()) {
            LocalDateTime target;
            String targetSource;
            try {
                target = parseTargetTime(timeText);
                targetSource = "по введенному времени: " + timeText;
            } catch (Exception e) {
                // 2) Часы и минуты без даты (ЧЧ:мм или ЧЧ:мм:сс) —
                //    ищем ПЕРВУЮ подходящую запись в логах и берем ее полное время
                LocalDateTime found = findFirstRecordTimeByClock(files, timeText);
                if (found == null) {
                    return "Не понял время «" + timeText + "».\n"
                            + "Поддерживаются форматы:\n"
                            + "  дд.ММ.гггг ЧЧ:мм:сс[.миллисек]  — напр. 11.09.2026 09:49:55.757\n"
                            + "  дд.ММ.гггг ЧЧ:мм                — напр. 11.09.2026 09:49\n"
                            + "  ЧЧ:мм или ЧЧ:мм:сс              — напр. 09:49 (возьмется первая запись на это время)\n"
                            + "Либо оставьте поле пустым и ищите по сумме или слову.";
                }
                target = found;
                targetSource = "по времени «" + timeText + "» -> первая подходящая запись: "
                        + found.format(TARGET_WITH_MS);
            }
            return analyzeOneWindow(target, targetSource, window, files);
        }

        // Время и сумма пустые — поиск по слову
        if (sumText.isEmpty() && !wordText.isEmpty()) {
            return analyzeByWord(files, wordText);
        }

        // 1) Время пустое — ищем все совпадения по сумме
        List<LocalDateTime> targets = findTimesBySum(files, sumText);
        if (targets.isEmpty()) {
            return "Сумма «" + sumText + "» не найдена ни в одном файле.\nПроверьте формат (36 или 1165,96) и выбранный файл.";
        }

        // 3) Несколько чеков — сначала весь первый, потом следующий и т.д.
        StringBuilder all = new StringBuilder();
        all.append("По сумме «").append(sumText).append("» найдено чеков/совпадений: ").append(targets.size());
        if (targets.size() > MAX_CHECKS_TO_SHOW) {
            all.append(" (показываю первые ").append(MAX_CHECKS_TO_SHOW).append(")");
        }
        all.append("\n\n");

        int show = Math.min(targets.size(), MAX_CHECKS_TO_SHOW);
        for (int i = 0; i < show; i++) {
            LocalDateTime t = targets.get(i);
            all.append("################ ЧЕК ").append(i + 1).append(" из ").append(targets.size())
               .append(" ################\n");
            all.append(analyzeOneWindow(t, "по сумме «" + sumText + "» (совпадение " + (i + 1) + "): "
                    + t.format(TARGET_WITH_MS), window, files, wordText));
            all.append("\n\n");
        }
        return all.toString();
    }

    /**
     * Поиск по слову/фразе: выводим все строки логов, содержащие введенный текст
     * (без учета регистра). Дополнительно отмечаем строки запуска программы,
     * чтобы было видно вылеты между чеками.
     */
    private String analyzeByWord(List<Path> files, String wordText) {
        Pattern needle = Pattern.compile(Pattern.quote(wordText),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

        List<String> hits = new ArrayList<>();
        List<String> starts = new ArrayList<>();
        for (Path f : files) {
            for (String raw : readAllLinesSafe(f)) {
                Matcher m = LOG_PATTERN.matcher(raw);
                String message = m.matches() ? m.group(2).trim() : raw.trim();
                String timeStr = m.matches() ? m.group(1) : "??";
                if (!needle.matcher(message).find()) {
                    continue;
                }
                hits.add("[" + timeStr + "] " + message);
                if (PROGRAM_START_PATTERN.matcher(message).find()) {
                    starts.add("[" + timeStr + "] " + message);
                }
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("===== ПОИСК ПО СЛОВУ: «").append(wordText).append("» =====\n");
        out.append("Файлов: ").append(files.size())
                .append(", найдено строк: ").append(hits.size()).append("\n\n");

        out.append("----- ЗАПУСК ПРОГРАММЫ (возможный вылет) (").append(starts.size()).append(") -----\n");
        if (starts.isEmpty()) {
            out.append("— в найденных строках запуска программы нет —\n");
        } else {
            starts.forEach(s -> out.append(s).append("\n"));
        }

        out.append("\n----- НАЙДЕННЫЕ СТРОКИ (").append(hits.size()).append(") -----\n");
        if (hits.isEmpty()) {
            out.append("Слово не найдено ни в одном файле.\n");
        } else {
            hits.forEach(s -> out.append(s).append("\n"));
        }
        return out.toString();
    }

    /** Отчет по одному окну [target-window; target+window]. */
    private String analyzeOneWindow(LocalDateTime target, String targetSource, int window, List<Path> files) {
        return analyzeOneWindow(target, targetSource, window, files, "");
    }

    /** Отчет по одному окну с дополнительным фильтром поиска по слову. */
    private String analyzeOneWindow(LocalDateTime target, String targetSource, int window,
                                    List<Path> files, String wordText) {
        LocalDateTime from = target.minusMinutes(window);
        LocalDateTime to = target.plusMinutes(window);

        List<LogLine> inWindow = new ArrayList<>();
        for (Path f : files) {
            for (String raw : readAllLinesSafe(f)) {
                Matcher m = LOG_PATTERN.matcher(raw);
                if (!m.matches()) {
                    continue;
                }
                LocalDateTime t;
                try {
                    t = LocalDateTime.parse(m.group(1), LOG_DATE_TIME);
                } catch (Exception e) {
                    continue;
                }
                if (!t.isBefore(from) && !t.isAfter(to)) {
                    inWindow.add(new LogLine(t, m.group(1), m.group(2).trim(), f.getFileName().toString()));
                }
            }
        }

        if (inWindow.isEmpty()) {
            return "Центр: " + targetSource
                    + "\nОкно: [" + from.format(TARGET_WITH_MS) + " ; " + to.format(TARGET_WITH_MS) + "]"
                    + "\nВ этом окне записей нет.";
        }
        Collections.sort(inWindow, (a, b) -> a.time.compareTo(b.time));

        List<String> errors = new ArrayList<>();
        List<String> marks = new ArrayList<>();
        Set<String> seenMarkValues = new LinkedHashSet<>(); // 1) маркировки без повторов
        Set<String> seenPartiaValues = new LinkedHashSet<>(); // 1) партии без повторов
        List<String> saleTypes = new ArrayList<>();
        List<String> userMessages = new ArrayList<>();
        List<String> discountLines = new ArrayList<>();
        Set<String> discountCards = new LinkedHashSet<>();
        Set<String> discountPhones = new LinkedHashSet<>();
        List<String> programStarts = new ArrayList<>();   // запуск программы = был вылет
        List<String> wordHits = new ArrayList<>();         // поиск по слову
        Pattern wordNeedle = wordText.isEmpty() ? null
                : Pattern.compile(Pattern.quote(wordText), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        List<Product> products = new ArrayList<>();
        Product current = null;

        for (LogLine l : inWindow) {
            String msg = l.message;
            // 2) Синхронизация скидок — не скидка, запоминаем флаг и не пишем в отчет о скидках
            boolean isSync = SYNC_PATTERN.matcher(msg).find();

            // Поиск по слову: строки, содержащие введенный текст
            if (wordNeedle != null && wordNeedle.matcher(msg).find()) {
                wordHits.add("[" + l.timeStr + "] " + msg);
            }

            // Запуск программы между чеками = признак вылета
            if (PROGRAM_START_PATTERN.matcher(msg).find()) {
                programStarts.add("[" + l.timeStr + "] " + msg);
            }

            Matcher saleM = SALE_BUTTON_PATTERN.matcher(msg);
            if (saleM.find()) {
                saleTypes.add("[" + l.timeStr + "] " + saleM.group(1).trim());
            }

            Matcher umM = USER_MSG_PATTERN.matcher(msg);
            if (umM.find() && !umM.group(1).trim().isEmpty()) {
                String userText = umM.group(1).trim();
                // Шумовые сообщения не показываем (напр. "Печатать кассовый чек на ККМ?")
                if (!USER_MSG_EXCLUDE_PATTERN.matcher(userText).find()) {
                    userMessages.add("[" + l.timeStr + "] " + userText);
                }
            }

            // 1) Скидка: нужна ТОЛЬКО какая скидка применена — карта или телефон.
            // Строки "Сумма без скидки / Сумма скидки: 0 / Сумма по цене..." — шум, не пишем.
            boolean isTech = TECH_PATTERN.matcher(msg).find();
            boolean cardFound = false;
            boolean phoneFound = false;
            Matcher dcM = DISCOUNT_APPLY_PATTERN.matcher(msg);
            if (dcM.find()) {
                discountCards.add(dcM.group(1).trim());
                cardFound = true;
            }
            Matcher dcGen = DISCOUNT_CARD_GENERIC_PATTERN.matcher(msg);
            if (dcGen.find() && !isSync && !isTech) {
                discountCards.add(dcGen.group(1).trim().replaceAll("\\s+", " "));
                cardFound = true;
            }
            Matcher phM = PHONE_GENERIC_PATTERN.matcher(msg);
            if (phM.find()) {
                discountPhones.add(phM.group(1).trim());
                phoneFound = true;
            }
            // В строки про скидку кладем только доказательства карты/телефона
            if ((cardFound || phoneFound) && !isSync && !isTech) {
                discountLines.add("[" + l.timeStr + "] " + msg);
            }

            if (ERROR_WORD.matcher(msg).find() || CHECK_FAIL_PATTERN.matcher(msg).find()) {
                errors.add("[" + l.timeStr + "] " + msg);
            }

            String mark = extractMark(msg);
            if (mark != null && seenMarkValues.add(mark)) {
                // 1) повторную маркировку не выводим, только первое вхождение
                marks.add("[" + l.timeStr + "] " + mark);
                current = new Product(l.timeStr);
                current.marking = mark;
                products.add(current);
            } else if (mark != null) {
                // маркировка уже была — привязываемся к существующему товару с ней
                for (Product p : products) {
                    if (mark.equals(p.marking)) {
                        current = p;
                        break;
                    }
                }
            }

            String partia = extractFirstGroup(PARTIA_PATTERN, msg);
            if (partia == null) {
                partia = extractFirstGroup(PARTIA_ALT_PATTERN, msg);
            }
            // 3) в начало номера партии добавляем 200 (если его там еще нет)
            if (partia != null) {
                partia = formatPartia(partia);
            }
            String qty = extractFirstGroup(QUANTITY_PATTERN, msg);
            String checkNum = extractFirstGroup(CHECK_NUM_PATTERN, msg);

            if (partia != null || qty != null || checkNum != null) {
                if (current == null) {
                    current = new Product(l.timeStr);
                    products.add(current);
                }
                if (partia != null && current.partia == null) {
                    // 1) если такая партия уже выводилась — переиспользуем её товар, дубль не создаем
                    Product existing = findByPartia(products, partia);
                    if (existing != null && existing != current && current.isEmptyExceptMarking()) {
                        // маркировку с нового (пустого) товара переносим в существующий, чтобы не потерять
                        if (existing.marking == null && current.marking != null) {
                            existing.marking = current.marking;
                        }
                        products.remove(current);
                        current = existing;
                    } else if (existing != null && existing != current) {
                        // текущий уже содержит данные — просто переходим на существующий
                        current = existing;
                    } else {
                        current.partia = partia;
                        seenPartiaValues.add(partia);
                    }
                } else if (partia != null && !partia.equals(current.partia)) {
                    Product existing = findByPartia(products, partia);
                    if (existing != null) {
                        current = existing;
                    } else {
                        current = new Product(l.timeStr);
                        current.partia = partia;
                        products.add(current);
                        seenPartiaValues.add(partia);
                    }
                }
                if (qty != null && current.quantity == null) {
                    current.quantity = qty;
                }
                if (checkNum != null) {
                    current.checkNum = checkNum;
                    current.checkLine = msg;
                }
                current.lastTimeStr = l.timeStr;
            }
        }

        String discountVerdict = calcDiscountVerdict(inWindow);

        StringBuilder out = new StringBuilder();
        out.append("Центр: ").append(targetSource).append("\n");
        out.append("Окно: [").append(from.format(TARGET_WITH_MS))
                .append(" ; ").append(to.format(TARGET_WITH_MS)).append("]\n");
        out.append("Файлов: ").append(files.size())
                .append(", строк в окне: ").append(inWindow.size()).append("\n\n");

        // Перезапуск программы (вылет) — самое важное, показываем первым
        out.append("===== ЗАПУСК ПРОГРАММЫ / ВЫЛЕТ (").append(programStarts.size()).append(") =====\n");
        if (programStarts.isEmpty()) {
            out.append("— в этом промежутке перезапусков не было —\n");
        } else {
            out.append("!! ВНИМАНИЕ: между пробитием чеков был вылет/перезапуск программы !!\n");
            programStarts.forEach(s -> out.append(s).append("\n"));
        }

        if (wordNeedle != null) {
            out.append("\n===== ПОИСК ПО СЛОВУ: «").append(wordText).append("» (")
               .append(wordHits.size()).append(") =====\n");
            if (wordHits.isEmpty()) {
                out.append("— в этом окне не найдено —\n");
            } else {
                wordHits.forEach(s -> out.append(s).append("\n"));
            }
        }

        out.append("\n===== ТИП ПРОДАЖИ (").append(saleTypes.size()).append(") =====\n");
        if (saleTypes.isEmpty()) {
            out.append("— не найден —\n");
        } else {
            saleTypes.forEach(s -> out.append(s).append("\n"));
        }

        out.append("\n===== СКИДКА =====\n");
        out.append(discountVerdict).append("\n");
        out.append("Дисконтные карты: ").append(discountCards.isEmpty() ? "N/A" : String.join(", ", discountCards)).append("\n");
        out.append("Телефоны: ").append(discountPhones.isEmpty() ? "N/A" : String.join(", ", discountPhones)).append("\n");
        if (discountLines.isEmpty()) {
            out.append("Строк про применение скидки в окне нет (синхронизация скидок не считается).\n");
        } else {
            out.append("--- строки про скидку (").append(discountLines.size()).append(") ---\n");
            discountLines.forEach(s -> out.append(s).append("\n"));
        }

        out.append("\n===== СООБЩЕНИЯ ПОЛЬЗОВАТЕЛЮ (").append(userMessages.size()).append(") =====\n");
        if (userMessages.isEmpty()) {
            out.append("— нет —\n");
        } else {
            userMessages.forEach(s -> out.append(s).append("\n"));
        }

        out.append("\n===== ОШИБКИ ЧЕКА (").append(errors.size()).append(") =====\n");
        if (errors.isEmpty()) {
            out.append("— нет —\n");
        } else {
            errors.forEach(s -> out.append(s).append("\n"));
        }

        out.append("\n===== МАРКИРОВКИ (").append(marks.size()).append(") =====\n");
        if (marks.isEmpty()) {
            out.append("— нет —\n");
        } else {
            marks.forEach(s -> out.append(s).append("\n"));
        }

        out.append("\n===== ТОВАРЫ: партия / количество (").append(products.size()).append(") =====\n");
        if (products.isEmpty()) {
            out.append("— нет —\n");
        } else {
            int i = 1;
            for (Product p : products) {
                out.append(i++).append(". [").append(p.lastTimeStr != null ? p.lastTimeStr : p.timeStr).append("] ");
                out.append("Партия: ").append(orNa(p.partia)).append(", ");
                out.append("Количество: ").append(orNa(p.quantity)).append(", ");
                out.append("Маркировка: ").append(orNa(p.marking));
                if (p.checkNum != null) {
                    out.append(", Чек №").append(p.checkNum);
                }
                out.append("\n");
                if (p.checkLine != null) {
                    out.append("    -> ").append(p.checkLine).append("\n");
                }
            }
        }
        return out.toString();
    }

    private static String calcDiscountVerdict(List<LogLine> inWindow) {
        double maxDiscount = 0;
        String maxStr = null;
        for (LogLine l : inWindow) {
            // синхронизацию не считаем скидкой
            if (SYNC_PATTERN.matcher(l.message).find()) {
                continue;
            }
            Matcher m = DISCOUNT_SUM_PATTERN.matcher(l.message);
            if (m.find()) {
                String raw = m.group(1).replace(" ", "").replace(" ", "").replace(',', '.');
                try {
                    double v = Double.parseDouble(raw);
                    if (v > maxDiscount) {
                        maxDiscount = v;
                        maxStr = m.group(1).trim();
                    }
                } catch (NumberFormatException ignored) {
                    maxStr = m.group(1).trim();
                }
            }
        }
        if (maxStr == null) {
            return "Скидка: НЕ НАЙДЕНА (нет строк «Сумма скидки» в окне, синхронизация не считается)";
        }
        if (maxDiscount > 0) {
            return "Скидка: БЫЛА, сумма скидки = " + maxStr;
        }
        return "Скидка: строки есть, но сумма = 0 (фактически без скидки)";
    }

    private List<Path> filesToScan() {
        if (chosenFile != null) {
            List<Path> one = new ArrayList<>();
            one.add(chosenFile);
            return one;
        }
        Path src = Paths.get("src");
        if (!Files.isDirectory(src)) {
            return new ArrayList<>();
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(src)) {
            List<Path> res = new ArrayList<>();
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .sorted()
                    .forEach(res::add);
            return res;
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static List<String> readAllLinesSafe(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            try {
                return Files.readAllLines(file, Charset.forName("windows-1251"));
            } catch (IOException ex) {
                return new ArrayList<>();
            }
        } catch (IOException e) {
            try {
                return Files.readAllLines(file, Charset.forName("windows-1251"));
            } catch (IOException ex) {
                return new ArrayList<>();
            }
        }
    }

    /** Разбор введенного времени с датой: дд.ММ.гггг ЧЧ:мм:сс[.миллисек] / дд.ММ.гггг ЧЧ:мм. */
    private static LocalDateTime parseTargetTime(String s) {
        s = s.trim();
        try {
            return LocalDateTime.parse(s, TARGET_WITH_MS);
        } catch (Exception e) {
            // падаем на вариант без миллисекунд
        }
        try {
            return LocalDateTime.parse(s, TARGET_NO_MS);
        } catch (Exception e) {
            // падаем на вариант только с часами и минутами
        }
        return LocalDateTime.parse(s, TARGET_HM);
    }

    /** Только часы и минуты: "ЧЧ:мм" или "ЧЧ:мм:сс" (без даты). */
    private static final DateTimeFormatter CLOCK_FULL =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter CLOCK_HM =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter TARGET_HM =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.ROOT);

    /** Разбирает "ЧЧ:мм" или "ЧЧ:мм:сс" в LocalTime-подобный набор чисел. */
    private static int[] parseClockParts(String s) {
        s = s.trim();
        // ЧЧ:мм
        if (s.matches("\\d{1,2}:\\d{2}")) {
            String[] p = s.split(":");
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), -1};
        }
        // ЧЧ:мм:сс
        if (s.matches("\\d{1,2}:\\d{2}:\\d{2}")) {
            String[] p = s.split(":");
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
        }
        return null;
    }

    /**
     * 2) Поиск ПЕРВОЙ подходящей записи в логах по часам и минутам.
     * Возвращает полное время (с датой) первой записи, чьи ЧЧ:мм совпали
     * с введенными. Секунды, если указаны, тоже сравниваем.
     * Если введены только часы — минуты игнорируем.
     */
    private static LocalDateTime findFirstRecordTimeByClock(List<Path> files, String clockText) {
        int[] parts = parseClockParts(clockText);
        if (parts == null) {
            return null;
        }
        int hh = parts[0];
        int mm = parts[1];
        int ss = parts[2]; // -1 если секунды не заданы

        LocalDateTime best = null;
        for (Path f : files) {
            for (String raw : readAllLinesSafe(f)) {
                Matcher m = LOG_PATTERN.matcher(raw);
                if (!m.matches()) {
                    continue;
                }
                LocalDateTime t;
                try {
                    t = LocalDateTime.parse(m.group(1), LOG_DATE_TIME);
                } catch (Exception e) {
                    continue;
                }
                if (t.getHour() != hh || t.getMinute() != mm) {
                    continue;
                }
                if (ss >= 0 && t.getSecond() != ss) {
                    continue;
                }
                // Берем самую раннюю подходящую запись
                if (best == null || t.isBefore(best)) {
                    best = t;
                }
            }
        }
        return best;
    }

    /**
     * 3) Ищем ВСЕ времена по сумме, а не только первое.
     * Совпадения ближе SAME_CHECK_GAP друг к другу считаем строками одного чека —
     * оставляем только первое, чтобы не дублировать отчет.
     */
    private static List<LocalDateTime> findTimesBySum(List<Path> files, String sumText) {
        String normSum = normalizeSum(sumText);
        List<LocalDateTime> all = new ArrayList<>();
        if (normSum.isEmpty()) {
            return all;
        }
        String altSum = normSum.contains(".") ? normSum.replace('.', ',') : normSum.replace(',', '.');

        Pattern sumContext = Pattern.compile("сумм|чек|позиция|оплат|итог",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

        for (Path f : files) {
            for (String raw : readAllLinesSafe(f)) {
                Matcher m = LOG_PATTERN.matcher(raw);
                if (!m.matches()) {
                    continue;
                }
                String msg = m.group(2);
                if (!sumContext.matcher(msg).find()) {
                    continue;
                }
                String compact = msg.replace(" ", "").replace(" ", "");
                if (compact.contains(normSum.replace(" ", ""))
                        || compact.contains(altSum.replace(" ", ""))) {
                    try {
                        all.add(LocalDateTime.parse(m.group(1), LOG_DATE_TIME));
                    } catch (Exception ignored) {
                        // кривая дата — пропускаем
                    }
                }
            }
        }

        Collections.sort(all);
        // Кластеризация: близкие совпадения = один чек
        List<LocalDateTime> uniq = new ArrayList<>();
        for (LocalDateTime t : all) {
            if (uniq.isEmpty()) {
                uniq.add(t);
            } else {
                LocalDateTime last = uniq.get(uniq.size() - 1);
                if (Duration.between(last, t).abs().compareTo(SAME_CHECK_GAP) > 0) {
                    uniq.add(t);
                }
            }
        }
        return uniq;
    }

    private static String normalizeSum(String s) {
        return s.trim().replace(" ", "").replace(" ", "").replace(',', '.');
    }

    private static String extractMark(String msg) {
        Matcher m = MARK_STRICT_PATTERN.matcher(msg);
        if (m.find()) {
            return m.group(1).trim();
        }
        m = MARK_CHECK_PATTERN.matcher(msg);
        if (m.find()) {
            return m.group(1).trim();
        }
        m = MARK_WORD_PATTERN.matcher(msg);
        if (m.find()) {
            return m.group(1).trim();
        }
        m = MARK_ANY_PATTERN.matcher(msg);
        if (m.find()) {
            String v = m.group(1).trim();
            if (v.length() >= 10 && !v.contains(" ")) {
                return v;
            }
        }
        return null;
    }

    private static String extractFirstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String orNa(String value) {
        return value == null || value.isEmpty() ? "N/A" : value;
    }

    /**
     * 3) К номеру партии спереди добавляем 200.
     * Если 200 уже есть — не дублируем.
     * Пример: 636581308 → 200636581308.
     */
    private static String formatPartia(String raw) {
        String digits = raw.trim().replaceAll("\\s+", "");
        if (digits.startsWith("200")) {
            return digits;
        }
        return "200" + digits;
    }

    /** 1) Поиск уже созданного товара с такой партией (для вывода без повторов). */
    private static Product findByPartia(List<Product> products, String partia) {
        for (Product p : products) {
            if (partia.equals(p.partia)) {
                return p;
            }
        }
        return null;
    }

    private static class LogLine {
        final LocalDateTime time;
        final String timeStr;
        final String message;
        final String fileName;

        LogLine(LocalDateTime time, String timeStr, String message, String fileName) {
            this.time = time;
            this.timeStr = timeStr;
            this.message = message;
            this.fileName = fileName;
        }
    }

    private static class Product {
        final String timeStr;
        String lastTimeStr;
        String marking;
        String partia;
        String quantity;
        String checkNum;
        String checkLine;

        Product(String timeStr) {
            this.timeStr = timeStr;
            this.lastTimeStr = timeStr;
        }

        /** Пустой товар: есть только время, без партии/количества/чека (маркировка может быть). */
        boolean isEmptyExceptMarking() {
            return partia == null && quantity == null && checkNum == null;
        }
    }
}
