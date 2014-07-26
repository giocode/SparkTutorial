import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf

import org.apache.spark.storage.StorageLevel

import org.apache.spark.sql.SQLContext






object ShoppersApp extends Application {

    // Create a Spark SparkContext
    val conf = new SparkConf().setAppName("Shoppers Application")
                              .set("spark.executor.memory", "1g")
                              .set("spark.storage.memoryFraction","0.3")
    val sc = new SparkContext(conf)


    /* History class: history of incentive offered a customer and information about the behavioral response to the offer

    customer_id - A unique id representing a customer
    chain - An integer representing a store chain
    offer_id - An id representing a certain offer
    market - An id representing a geographical region
    repeattrips - The number of times the customer made a repeat purchase
    repeater - A boolean, equal to repeattrips > 0
    offerdate - The date a customer received the offer)
    */ 
    case class History(customer_id: String, chain: Int, offer_id: String, market: String, repeattrips: Int, repeater: Boolean, offerdate: String) 

    /* Transaction 
    customer_id - see above
    chain - see above
    dept - An aggregate grouping of the Category (e.g. water)
    category - The product category (e.g. sparkling water)
    company - An id of the company that sells the item
    brand - An id of the brand to which the item belongs
    date - The date of purchase
    productsize - The amount of the product purchase (e.g. 16 oz of water)
    productmeasure - The units of the product purchase (e.g. ounces)
    purchasequantity - The number of units purchased
    purchaseamount - The dollar amount of the purchase
    */ 
    case class Transaction(customer_id: String, chain: Int, dept: String, category: String, company: String, brand: String,
                             date: String, productsize: Double, productmeasure: String, purchasequantity: Int, purchaseamount: Double)


    /* Offer class: 
    offer_id - see above
    category - see above
    quantity - The number of units one must purchase to get the discount
    company - see above
    offervalue - The dollar value of the offer
    brand - see above
    */ 
    case class Offer(offer_id: String, category: String, quantity: Int, company: String, offervalue: Double, brand: String)


    // Load the CSV files as texts


    // RDD of case objects of type History
    val history = sc.textFile("../data/shoppers/history") map { line => 
        val h = line.split(',')
        def isRepeater(s: String) = s == 't'
        History(h(0), h(1).toInt, h(2), h(3), h(4).toInt, isRepeater(h(5)), h(6))
    } 
    
    // RDD of case objects of type Offer
    val offers = sc.textFile("../data/shoppers/offers") map { line => 
        val o = line.split(',')
        Offer(o(0), o(1), o(2).toInt, o(3), o(4).toDouble, o(5))
    } 

    


    // ----------------------------------- YOUR CODE FOLLOWS HERE ------------------------------------- 


    // Q1: Create RDDs of key-value pairs for 'history' and 'offers' and join them into histOffers Pair RDD
    val histOffers = (history map (h => (h.offer_id, h))) join (offers map (o => (o.offer_id, o))) 
    histOffers.cache()


    // Q2: Find the popular offers that resulted in 5 most repeattrips and return a collection of 3-tuple (customer_id, offer_id, repeattrips)
    val top5Offers: Array[(String, String, Int, Double)] = histOffers.groupBy{case (_, (h,_)) => h.repeattrips}.
                                                                      sortByKey(false).take(5).flatMap(t => t._2).
                                                                      map{case (id, (h,o)) => (h.customer_id, id, h.repeattrips, o.offervalue)}


    // Then print these Top 5 Offers
    top5Offers.foreach { case (customer, offer, repeattrips, value) => 
        println("Customer #" + customer + " saved " + value + " dollars on offer #" + offer + " and later made repeteated purchases " + repeattrips + " times")
    }

    /*  Should print: 
            Customer #3465135195 saved 0.75 dollars on offer #1197502 and later made repeteated purchases 2124 times
            Customer #3450535153 saved 0.75 dollars on offer #1197502 and later made repeteated purchases 1418 times
            Customer #4427711419 saved 1.0 dollars on offer #1203052 and later made repeteated purchases 549 times
            Customer #4176323168 saved 1.0 dollars on offer #1203052 and later made repeteated purchases 104 times
            Customer #239276354 saved 0.75 dollars on offer #1197502 and later made repeteated purchases 87 times
    */ 


    // Extract top 5 customers 
    val top5Customers = top5Offers.map{ case (c: String, _, _, _) => c}

    // Optional: find the maximum number of repeattrips generated by offers with a 5$ discount value
    val maxRepeatsFromFiveBucks = {
        val offersFiveBucks = histOffers.filter {case (_, (h,o)) => o.offervalue == 3}
                                        .groupBy {case (id, (h, o)) => h.repeattrips}
                                        .sortByKey(false).first()
        offersFiveBucks._1
    }
    println("\nCoupons with 5 dollar value yield a maximum of only " + maxRepeatsFromFiveBucks + " repeated purchases")



    // Let us now look into the purchase history of these top 5 Customers prior to their offered incentive

    // The dataset containing the transactions of these top 5 customers are given to you as textfiles in topTransactions folder 
    // Let us first load into RDD of case objects of type Transaction
    
    val inputTrans = sc.textFile("../data/shoppers/topTransactions")   

    val transactions = (inputTrans map { line => 
        val t = line.split(',')
        Transaction(t(0), t(1).toInt, t(2), t(3), t(4), t(5), t(6), t(7).toDouble, t(8), t(9).toInt, t(10).toDouble)
    })


    // Q4: Compute the average spending Per purchase trip (assuming one trip per day) of these 5 customers

    // To compute this one, we will divide it into multiple tasks: 
    val avgSpendingPerTrip = {

        // Map the transactions by Customer and Date, i.e. the key consists of a pair (customer_id, date)
        val transKeyedByCustomerAndDate = transactions map {t => ((t.customer_id, t.date), t)}

        // Compute the total spending per trip per customer using combineByKey or reduceByKey
        val spendingPerTrip = transKeyedByCustomerAndDate.combineByKey(
          (t) => (t.purchaseamount), 
          (acc: Double, t) => (acc + t.purchaseamount),
          (a1: Double, a2: Double) => (a1 + a2))

        // Transform spendingPerTrip into an RDD of pairs (customer_id, totalSpendingValue) for each trip 
        val spendingsPerCustomer = spendingPerTrip map {case (trip: (String, String), value: Double) => (trip._1, value)}

        // Compute the average spending per trip for each customer
        val averageSpendings = spendingsPerCustomer.mapValues(s => (s,1))
                                                     .reduceByKey{(a: (Double, Int), b: (Double, Int)) => (a._1 + b._1, a._2 + b._2)}
                                                     .mapValues(t => t._1 / t._2)

        averageSpendings.collect()  // collect result from RDD averageSpendings
    }   

    avgSpendingPerTrip.foreach{case (customer, avg) => println("Customer #" + customer + " : " + avg)}
        

    // Bonus Question: For each customer, compute the average spending Per Purchase trip only for items that have  
    // the same Category as the one for which they were offered a coupon, e.g. same category in transactions and history. 


}


