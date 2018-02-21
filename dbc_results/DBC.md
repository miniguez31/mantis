# Results notes appliying DBC in BlockHeader


* Adding precondition 256-bit hash length for parentHash
``` 
result: Total 1066, Failed 212, Errors 0, Passed 854
```

* Adding precondition 256-bit hash length for ommersHash
``` 
result: Total 1066, Failed 213, Errors 0, Passed 853
```

* Adding precondition 160-bit length for beneficiary
``` 
result: Total 1066, Failed 213, Errors 0, Passed 853
```

* Adding precondition 256-bit hash length for stateRoot
``` 
result: Total 1066, Failed 215, Errors 0, Passed 851
``` 

* Adding precondition 256-bit hash length for transactionsRoot
```
result: Total 1066, Failed 214, Errors 0, Passed 852
```

* Adding precondition 256-bit hash length for receiptsRoot
```
result: Total 1066, Failed 214, Errors 0, Passed 852
```

* Adding precondition more than or equal to 0 for difficulty
```
result: Total 1066, Failed 0, Errors 0, Passed 1066
```

* Adding precondition more than or equal to 0 for number
```
result: Total 1066, Failed 1, Errors 0, Passed 1065
```