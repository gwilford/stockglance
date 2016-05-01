// StockGlance.java
//
// Copyright (c) 2015-16, James Larus
//  All rights reserved.
//
//  Redistribution and use in source and binary forms, with or without
//  modification, are permitted provided that the following conditions are
//  met:
//
//  1. Redistributions of source code must retain the above copyright
//  notice, this list of conditions and the following disclaimer.
//
//  2. Redistributions in binary form must reproduce the above copyright
//  notice, this list of conditions and the following disclaimer in the
//  documentation and/or other materials provided with the distribution.
//
//  3. Neither the name of the copyright holder nor the names of its
//  contributors may be used to endorse or promote products derived from
//  this software without specific prior written permission.
//
//  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
//  "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
//  LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
//  A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
//  HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
//  SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
//  LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
//  DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
//  THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
//  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
//  OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package com.moneydance.modules.features.stockglance;

import com.infinitekind.moneydance.model.*;
import com.infinitekind.util.StringUtils;
import com.moneydance.apps.md.view.HomePageView;
import com.moneydance.awt.CollapsibleRefresher;
import com.moneydance.apps.md.view.gui.MoneydanceLAF;

import java.util.*;
import java.text.*;
import java.awt.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.*;


// Home page component to display active stock prices and returns.

class StockGlance implements HomePageView {
    private AccountBook book;
    private JTable table;
    private JScrollPane tablePane;
    private final currencyCallback currencyTableCallback = new currencyCallback(this);
    private final accountCallback allAccountsCallback = new accountCallback(this);
    private final CollapsibleRefresher refresher;
    private final Color lightLightGray = new Color(0xDCDCDC);

    StockGlance() {
        this.refresher = new CollapsibleRefresher(StockGlance.this::reallyRefresh);
    }


    //
    // HomePageView interface:
    //

    // Returns a unique identifier for this view.
    @Override
    public String getID() {
        return "StockGlance";
    }

    // Returns a short descriptive name of this view.
    @Override
    public String toString() {
        return "Stock Glance";
    }

    // Returns a GUI component that provides a view of the info pane for the given data file.
    @Override
    public javax.swing.JComponent getGUIView(AccountBook book) {
        if (tablePane == null) {
            synchronized (this) {
                this.book = book;
                Vector<Vector<Object>> data = getTableData(book);
                DefaultTableModel tableModel = makeTableModel(data);
                table = makeTable(tableModel);
                tablePane = new JScrollPane(table);
                tablePane.setBorder(BorderFactory.createCompoundBorder(MoneydanceLAF.homePageBorder, BorderFactory.createEmptyBorder(0, 0, 0, 0)));
            }
        }
        return tablePane;
    }

    // Sets the view as active or inactive. When not active, a view should not have any registered listeners
    // with other parts of the program. This will be called when an view is added to the home page,
    // or the home page is refreshed after not being visible for a while.
    @Override
    public void setActive(boolean active) {
        if (book != null) {
            book.getCurrencies().removeCurrencyListener(currencyTableCallback); // At most one listener
            book.removeAccountListener(allAccountsCallback);
            if (active) {
                book.getCurrencies().addCurrencyListener(currencyTableCallback);
                book.addAccountListener(allAccountsCallback);
            }
        }
    }

    // Forces a refresh of the information in the view. For example, this is called after the preferences are updated.
    // Like the other home page controls, we actually do this lazily to avoid repeatedly recalculating after stock
    // price updates.
    @Override
    public void refresh() {
        refresher.enqueueRefresh();
    }

    // Actually recompute and redisplay table.
    private void reallyRefresh() {
        synchronized (this) {
            Vector<Vector<Object>> newData = getTableData(book);
            ((DefaultTableModel)table.getModel()).setDataVector(newData, columnNames);
        }
        table.validate();
    }

    // Called when the view should clean up everything. For example, this is called when a file is closed and the GUI
    // is reset. The view should disconnect from any resources that are associated with the currently opened data file.
    @Override
    public void reset() {
        setActive(false);
        tablePane.removeAll();
        tablePane = null;
        table = null;
    }


    //
    // Implementation:
    //

    // Per column metadata
    private final String[] names = {"Stock", "Day%", "Week%", "Month%", "Year%"};
    private final String[] types = {"Text", "Percent", "Percent", "Percent", "Percent"};
    private final Class[] classes = {String.class, Double.class, Double.class, Double.class, Double.class};
    private final Vector<String> columnNames = new Vector<>(Arrays.asList(names));

    // Per row metadata
    private final Vector<CurrencyType> securityCurrencies = new Vector<>(); // Type of security in each row

    private DefaultTableModel makeTableModel(Vector<Vector<Object>> data) {
        return new DefaultTableModel(data, columnNames) {
            @Override
            public Class<?> getColumnClass(int col) {
                return classes[col];
            }
        };
    }

    private JTable makeTable(TableModel tableModel) {
        JTable table = new JTable(tableModel) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);
                // Alternating row color bands
                if (!isRowSelected(row))
                    c.setBackground(row % 2 == 0 ? getBackground() : lightLightGray);
                // Percent needs to be thinner than other columns
                if (types[column].equals("Percent")) {
                    TableColumn tableColumn = getColumnModel().getColumn(column);
		    tableColumn.setMaxWidth( 50 );
                }
                return c;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            // Rendering depends on row (i.e. security's currency) as well as column
            @Override
            public TableCellRenderer getCellRenderer(int row, int column) {
                DefaultTableCellRenderer renderer;
                switch (types[column]) {
                    case "Text":
                        renderer = new DefaultTableCellRenderer();
                        renderer.setHorizontalAlignment(JLabel.LEFT);
                        break;

                    case "Currency0":
                    case "Currency2":
                        CurrencyType security = securityCurrencies.get(row);
                        CurrencyTable table = security.getTable();
                        CurrencyType curr = table.getBaseType();
                        renderer = new CurrencyRenderer(curr, types[column].equals("Currency0"));
                        renderer.setHorizontalAlignment(JLabel.RIGHT);
                        break;

                    case "Percent":
                        renderer = new PercentRenderer();
                        renderer.setHorizontalAlignment(JLabel.RIGHT);
                        break;

                    default:
                        throw new UnsupportedOperationException();
                }
                return renderer;
            }
        };

        TableColumnModel cm = table.getColumnModel();
        for (int i = 0; i < cm.getColumnCount(); i ++) {
            TableColumn col = cm.getColumn(i);
            col.setHeaderRenderer(new HeaderRenderer());
        }

        table.setAutoCreateRowSorter(true);
        table.getRowSorter().toggleSortOrder(0); // Default is to sort by symbol
        return table;
    }


    private Vector<Vector<Object>> getTableData(AccountBook book) {
        CurrencyTable ct = book.getCurrencies();
        java.util.List<CurrencyType> allCurrencies = ct.getAllCurrencies();

        GregorianCalendar cal = new GregorianCalendar();
        int today = makeDateInt(cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1, // Jan == 1
                cal.get(Calendar.DAY_OF_MONTH));
        Vector<Vector<Object>> data = new Vector<>();

        for (CurrencyType curr : allCurrencies) {
            if (!curr.getHideInUI() && curr.getCurrencyType() == CurrencyType.Type.SECURITY) {
                Double price = priceOrNaN(curr, today, 0);
                Double price1 = priceOrNaN(curr, today, 1);
                Double price7 = priceOrNaN(curr, today, 7);
                Double price30 = priceOrNaN(curr, today, 30);
                Double price365 = priceOrNaN(curr, today, 365);

                if (!Double.isNaN(price) && (!Double.isNaN(price1) || !Double.isNaN(price7)
                        || !Double.isNaN(price30) || !Double.isNaN(price365))) {
                    Vector<Object> entry = new Vector<>();

                    entry.add(curr.getName());
                    entry.add((price - price1) / price1);
                    entry.add((price - price7) / price7);
                    entry.add((price - price30) / price30);
                    entry.add((price - price365) / price365);

                    data.add(entry);
                    securityCurrencies.add(curr);
                }
            }
        }

        return data;
    }

    private Double priceOrNaN(CurrencyType curr, int date, int delta) {
        try {
            int backDate = backDays(date, delta);
            if (haveSnapshotWithinWeek(curr, backDate)) {
                return 1.0 / curr.getUserRateByDateInt(backDate);
            } else {
                return Double.NaN;
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            return Double.NaN;
        }
    }

    // Date int is yyyymmdd
    private int makeDateInt(int year, int month, int day) {
        return year * 10000 + month * 100 + day;
    }

    private final static int[] DaysInMonth = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

    private boolean isLeapYear(int year) {
        return (year % 4 == 0) && (year % 100 != 0);
    }

    // Month starts at 1 (Jan), but 0 = Dec, -1 = Nov, ...
    private int daysInMonth(int month, int year) {
        if (month <= 0) {
            month += 12;
        }
        if (month == 2 && isLeapYear(year)) {
            return 29;
        } else {
            return DaysInMonth[month - 1];
        }
    }

    // Return the DateInt that is delta days before dateInt
    public int backDays(int dateInt, int delta) {
        int year = dateInt / 10000;
        int month = (dateInt / 100) % 100;
        int day = dateInt % 100;
        int daysPerYear = isLeapYear(year) ? 366 : 365;

        while (delta >= daysPerYear) {
            delta -= daysPerYear;
            year -= 1;
            daysPerYear = isLeapYear(year) ? 366 : 365;
        }
        while (delta >= daysInMonth(month - 1, year)) {
            delta -= daysInMonth(month - 1, year);
            month -= 1;
            if (month == 0) {
                month = 12;
                year -= 1;
            }
        }
        day -= delta;
        if (day <= 0) {
            month -= 1;
            if (month == 0) {
                month = 12;
                year -= 1;
            }
            day += daysInMonth(month, year);
        }

        return makeDateInt(year, month, day);
    }

    // MD function getRawRateByDateInt(int dt) returns last known value, even if wildly out of date.
    // Return true if the snapshots contain a rate within a week before the date.
    private boolean haveSnapshotWithinWeek(CurrencyType curr, int date) {
        List<CurrencySnapshot> snapshots = curr.getSnapshots();
        for (CurrencySnapshot snap : snapshots) {
            if (Math.abs(date - snap.getDateInt()) <= 7) { // within a week
                return true;
            }
        }
        return snapshots.isEmpty(); // If no snapshots, use fixed rate; otherwise didn't find snapshot
    }

    //
    // Private classes:
    //

    // CurrencyListener
    static private class currencyCallback implements CurrencyListener {
        private final StockGlance thisSG;

        currencyCallback(StockGlance sg) {
            thisSG = sg;
        }

        public void currencyTableModified(CurrencyTable table) {
            thisSG.refresh();
        }
    }

    // AccountListener
    static private class accountCallback implements AccountListener {
        private final StockGlance thisSG;

        accountCallback(StockGlance sg) {
            thisSG = sg;
        }

        public void accountAdded(Account parentAccount, Account newAccount) {
            thisSG.refresh();
        }

        public void accountBalanceChanged(Account newAccount) {
            thisSG.refresh();
        }

        public void accountDeleted(Account parentAccount, Account newAccount) {
            thisSG.refresh();
        }

        public void accountModified(Account newAccount) {
            thisSG.refresh();
        }
    }

    // Render a currency with given number of fractional digits. NaN or null is an empty cell.
    // Negative values are red.
    static private class CurrencyRenderer extends DefaultTableCellRenderer {
        private final boolean noDecimals;
        private final CurrencyType relativeTo;
        private final char decimalSeparator = '.'; // ToDo: Set from preferences (how?)
        private final NumberFormat noDecimalFormatter;


        CurrencyRenderer(CurrencyType currency, boolean noDecimals) {
            super();
            this.noDecimals = noDecimals;
            CurrencyTable ct = currency.getTable();
            relativeTo = ct.getBaseType();
            noDecimalFormatter = NumberFormat.getNumberInstance();
            noDecimalFormatter.setMinimumFractionDigits(0);
            noDecimalFormatter.setMaximumFractionDigits(0);
        }

        boolean isZero(Double value) {
            return Math.abs(value) < 0.01;
        }

        @Override
        public void setValue(Object value) {
            if (value == null) {
                setText("");
            } else if (Double.isNaN((Double) value)) {
                setText("");
            } else {
                if (isZero((Double) value)) {
                    value = 0.0;
                }
                if (noDecimals) {
                    // MD format functions can't print comma-separated values without a decimal point so
                    // we have to do it ourselves
                    setText(relativeTo.getPrefix() + " " + noDecimalFormatter.format(value) + relativeTo.getSuffix());
                } else {
                    final long longValue = (long) ((Double) value * 100);
                    setText(relativeTo.formatFancy(longValue, decimalSeparator));
                }
                if ((Double) value < 0.0) {
                    setForeground(Color.RED);
                } else {
                    setForeground(Color.BLACK);
                }
            }
        }
    }

    // Render a percentage with 2 digits after the decimal point. Conventions as CurrencyRenderer
    static private class PercentRenderer extends DefaultTableCellRenderer {
        private final char decimalSeparator = '.'; // ToDo: Set from preferences (how?)

        PercentRenderer() {
            super();
        }

        private boolean isZero(Double value) {
            return Math.abs(value) < 0.0001;
        }

        @Override
        public void setValue(Object value) {
            if (value == null) {
                setText("");
            } else if (Double.isNaN((Double) value)) {
                setText("");
            } else {
                if (isZero((Double) value)) {
                    value = 0.0;
                }
                //setText(StringUtils.formatPercentage((Double) value, decimalSeparator) + "%");
                setText(StringUtils.formatPercentage((Double) value, decimalSeparator));
                if ((Double) value < 0.0) {
                    setForeground(Color.RED);
                } else {
                    setForeground(Color.BLACK);
                }
            }
        }
    }

    static private class HeaderRenderer extends DefaultTableCellRenderer {
        HeaderRenderer() {
            super();
            setForeground(Color.BLACK);
            setBackground(Color.lightGray);
            setHorizontalAlignment(JLabel.CENTER);
        }

        @Override
        public void setValue(Object value) {
            super.setValue(value);
        }
    }
}
